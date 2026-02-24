/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.arakelian.json;

import java.io.Closeable;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.arakelian.core.utils.DateUtils;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.io.BaseEncoding;

/**
 * High-performance JSON writer that serializes Java objects to JSON with support for pretty-printing
 * and options to skip null or empty values. Operates on any {@link Writer} implementation and
 * minimizes intermediate {@link String} allocations.
 *
 * @param <W> the type of {@link Writer} to write JSON output to
 */
public class JsonWriter<W extends Writer> implements Closeable {
    private static enum Container {
        DOCUMENT, OBJECT, ARRAY;
    }

    private class JsonStringEscapingWriter extends FilterWriter {
        public JsonStringEscapingWriter(final W out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            // ignore close of underlying input stream
        }

        @Override
        public void flush() throws IOException {
            super.flush();
        }

        @Override
        public void write(final char[] cbuf, final int off, final int len) throws IOException {
            for (int i = 0; i < len; i++) {
                internalWriteEscapedChar(cbuf[off + i]);
            }
        }

        @Override
        public void write(final int c) throws IOException {
            internalWriteEscapedChar((char) c);
        }

        @Override
        public void write(final String str, final int off, final int len) throws IOException {
            for (int i = 0; i < len; i++) {
                internalWriteEscapedChar(str.charAt(off + i));
            }
        }
    }

    private static enum Separator {
        START, COMMA;
    }

    private final class State {
        /** Depth **/
        private final int depth;

        /** What type of container **/
        private Container container;

        /** Number of items in container **/
        private int size;

        /** Copy of the last {@link CharSequence} passed to {@link #writeKey(Object)} **/
        private char[] key;

        /** Length of last {@link CharSequence} passed to {@link #writeKey(Object)}} **/
        private int keyLen;

        /** Required separator **/
        private Separator separator;

        /**
         * Reference to last String passed to {@link #writeKey(Object)}. Will be null if a
         * CharSequence was used instead.
         **/
        private String str;

        public State(final int depth) {
            this.depth = depth;
        }

        public void afterValue() {
            separator = Separator.COMMA;
            size++;
        }

        public void beforeValue() throws IOException {
            if (container == Container.DOCUMENT) {
                Preconditions.checkState(size == 0, "there can be only one root level value");
            }
            final boolean keyed = beforeValue(true);
            switch (container) {
            case DOCUMENT:
            case ARRAY:
                Preconditions.checkState(!keyed, "key value pairs only valid in object");
                break;
            case OBJECT:
                Preconditions.checkState(keyed, "key value pairs only valid in object");
                break;
            }
        }

        private boolean beforeValue(final boolean flush) throws IOException {
            if (depth != 0) {
                state[depth - 1].beforeValue(true);
            }

            if (flush) {
                writeSeparator();
                return flushKey();
            }
            return false;
        }

        private boolean flushKey() throws IOException {
            if (keyLen != 0) {
                internalWriteEscapedString(key, 0, keyLen);
                keyLen = 0;
                // fall through
            } else if (str != null) {
                internalWriteEscapedString(str);
                str = null;
                // fall through
            } else {
                return false;
            }

            if (pretty) {
                // space before colon matches Jackson
                writer.write(" ");
            }
            writer.write(":");
            if (pretty) {
                writer.write(" ");
            }
            return true;
        }

        private final void nextLine() throws IOException {
            nextLine(depth);
        }

        private final void nextLine(final int depth) throws IOException {
            writeNewline();
            if (pretty) {
                for (int i = 0; i < depth; i++) {
                    writer.write("  ");
                }
            }
        }

        public void reset(final Container container) {
            this.container = Preconditions.checkNotNull(container);
            size = 0;
            keyLen = 0;
            str = null;
            separator = Separator.START;
        }

        public void startArray() {
            reset(Container.ARRAY);
        }

        public void startObject() {
            reset(Container.OBJECT);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this) //
                    .omitNullValues() //
                    .add("depth", depth) //
                    .add("container", container) //
                    .add("size", size) //
                    .toString();
        }

        public boolean writeEndArray() throws IOException {
            Preconditions.checkState(container == Container.ARRAY, "No matching writeStartArray");
            if (separator == Separator.START) {
                // no values were written
                if (skipEmpty) {
                    return false;
                }
                beforeValue(false);
                writer.write("[]");
            } else {
                nextLine(depth - 1);
                writer.write(']');
            }
            return true;
        }

        public boolean writeEndObject() throws IOException {
            Preconditions.checkState(container == Container.OBJECT, "No matching writeStartObject");
            if (separator == Separator.START) {
                // no key/value pairs were written
                if (skipEmpty) {
                    return false;
                }
                beforeValue(false);
                writer.write("{}");
            } else {
                nextLine(depth - 1);
                writer.write('}');
            }
            return true;
        }

        public final void writeKey(final Object key) {
            Preconditions.checkState(container == Container.OBJECT, "key value pairs only valid in object");
            if (key instanceof CharSequence) {
                // we are trying hard to avoid allocating stings, so we will store the key
                final CharSequence csq = (CharSequence) key;
                final int length = csq.length();
                final int capacity = this.key != null ? this.key.length : 0;
                if (length > capacity) {
                    // allocate memory to hold key in multiples of 128
                    final int size = (length + 127) / 128 * 128;
                    this.key = new char[size];
                }

                // copy source key
                for (int i = 0; i < length; i++) {
                    this.key[i] = csq.charAt(i);
                }
                this.keyLen = length;
            } else {
                // forced to use toString
                this.str = String.valueOf(key);
                this.keyLen = 0;
            }
        }

        private void writeSeparator() throws IOException {
            if (separator == Separator.START) {
                switch (container) {
                case ARRAY:
                    writer.write('[');
                    nextLine();
                    break;
                case OBJECT:
                    writer.write('{');
                    nextLine();
                    break;
                case DOCUMENT:
                    Preconditions.checkState(keyLen == 0 && str == null, "Invalid JSON");
                    break;
                }
            } else if (separator == Separator.COMMA) {
                writer.write(',');
                nextLine();
            }
            separator = null;
        }
    }

    /** When we encode strings, we always specify UTF8 encoding */
    private static final String UTF8_ENCODING = "UTF-8";

    /** When we encode strings, we always specify UTF8 encoding */
    public static final Charset UTF8_CHARSET = Charset.forName(UTF8_ENCODING);

    private final static char[] DIGIT_TO_CHAR = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B',
            'C', 'D', 'E', 'F' };

    /**
     * Convenience function for building a Json object string.
     *
     * @param pretty
     *            true if pretty output
     * @param keysValues
     *            ) list of alternating key-value pairs
     * @return a JSON object consisting of the given key value pairs
     */
    public static String keyValuesToString(final boolean pretty, final Object... keysValues) {
        final int size = keysValues != null ? keysValues.length : 0;
        if (size == 0) {
            // empty map
            return "{}";
        }
        try (final JsonWriter<StringWriter> writer = new JsonWriter<>(new StringWriter())) {
            writer.withSkipEmpty(true).withPretty(pretty);
            writer.writeStartObject();
            for (int i = 0; i < size;) {
                final Object key = keysValues[i++];
                final Object value = i < size ? keysValues[i++] : null;
                writer.writeKey(key);
                writer.writeObject(value);
            }
            writer.writeEndObject();
            return writer.getWriter().toString();
        } catch (final IOException e) {
            // we're writing to a string buffer and shouldn't have an error
            throw new RuntimeException("Unexpected exception while generating JSON", e);
        }
    }

    /**
     * Convenience function for converting an object into a Json string
     *
     * @param value
     *            Java object to be converted
     * @param pretty
     *            true if pretty output
     * @return a JSON representation of the given object
     * @throws IOException
     *             if an error occurs during serialization of the object
     */
    public static String toString(final Object value, final boolean pretty) throws IOException {
        try (final JsonWriter<StringWriter> writer = new JsonWriter<>(new StringWriter())) {
            writer.withSkipEmpty(true).withPretty(pretty);
            writer.writeObject(value);
            return writer.getWriter().toString();
        }
    }

    private W writer;

    private int indent;

    private boolean pretty = true;

    @SuppressWarnings("unchecked")
    private final State[] state = new JsonWriter.State[32];

    /** True to skip null values **/
    private boolean skipNulls = false;

    /** True to skip empty values **/
    private boolean skipEmpty = false;

    /**
     * Constructs a new {@code JsonWriter} with no underlying writer set.
     */
    public JsonWriter() {
        reset();
    }

    /**
     * Constructs a new {@code JsonWriter} that writes to the given writer.
     *
     * @param writer the writer to output JSON to
     */
    public JsonWriter(final W writer) {
        setWriter(writer);
        reset();
    }

    private final void afterValue() {
        this.state[indent].afterValue();
    }

    private final void beforeValue() throws IOException {
        this.state[indent].beforeValue();
    }

    @Override
    public void close() throws IOException {
        try {
            if (writer != null) {
                writer.close();
            }
        } finally {
            writer = null;
            reset();
        }
    }

    public final JsonWriter<W> flush() throws IOException {
        writer.flush();
        return this;
    }

    public final W getWriter() {
        return writer;
    }

    private final void internalWriteEscapedChar(final char ch) throws IOException {
        // http://www.ecma-international.org/publications/files/ECMA-ST/ECMA-404.pdf
        // note: we are not required to escape forward slash (e.g. solidus)
        switch (ch) {
        case '"':
            writer.write("\\\"");
            break;
        case '\\':
            writer.write("\\\\");
            break;
        case '\b':
            writer.write("\\b");
            break;
        case '\f':
            writer.write("\\f");
            break;
        case '\n':
            writer.write("\\n");
            break;
        case '\r':
            writer.write("\\r");
            break;
        case '\t':
            writer.write("\\t");
            break;
        default:
            // Reference: http://www.unicode.org/versions/Unicode5.1.0/
            if (ch <= '\u001F' || ch >= '\u007F') {
                writer.write("\\u");
                internalWriteHex(ch, 4);
            } else {
                writer.write(ch);
            }
        }
    }

    private final void internalWriteEscapedString(final char[] chars, final int offset, final int length)
            throws IOException {
        writer.write('\"');
        for (int i = offset; i < length; i++) {
            final char ch = chars[i];
            internalWriteEscapedChar(ch);
        }
        writer.write('\"');
    }

    private final void internalWriteEscapedString(final CharSequence csq) throws IOException {
        writer.write('\"');
        for (int i = 0, len = csq != null ? csq.length() : 0; i < len; i++) {
            final char ch = csq.charAt(i);
            internalWriteEscapedChar(ch);
        }
        writer.write('\"');
    }

    private final int internalWriteHex(final int l1, int minLength) throws IOException {
        if (l1 >= 16) {
            final int l2 = l1 / 16;
            minLength = internalWriteHex(l2, minLength - 1);
            while (minLength > 1) {
                writer.write('0');
                minLength--;
            }
            writer.write(DIGIT_TO_CHAR[l1 - l2 * 16]);
            minLength--;
        } else {
            while (minLength > 1) {
                writer.write('0');
                minLength--;
            }
            writer.write(DIGIT_TO_CHAR[l1]);
            minLength--;
        }
        return minLength;
    }

    private void internalWriteUnescapedString(final CharSequence csq) throws IOException {
        for (int i = 0, len = csq != null ? csq.length() : 0; i < len; i++) {
            final char ch = csq.charAt(i);
            writer.write(ch);
        }
    }

    /**
     * Returns {@code true} if the given value is considered empty. Null values, empty strings, empty
     * collections, empty maps, and non-finite floating point values are considered empty.
     *
     * @param value the value to test
     * @return {@code true} if the value is empty
     */
    public final boolean isEmpty(final Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof CharSequence) {
            final CharSequence csq = (CharSequence) value;
            return csq.length() == 0;
        }

        if (value instanceof Double) {
            final Double d = (Double) value;
            if (d.isInfinite() || d.isNaN()) {
                return true;
            }
            return false;
        }

        if (value instanceof Float) {
            final Float f = (Float) value;
            if (f.isInfinite() || f.isNaN()) {
                return true;
            }
            return false;
        }

        if (value instanceof Map) {
            final Map m = (Map) value;
            return m.size() == 0;
        }

        if (value instanceof List) {
            final List l = (List) value;
            return l.size() == 0;
        }

        return false;
    }

    /**
     * Return true if the given value is null
     *
     * @param value
     *            value to be tested
     * @return true if the given value is null
     */
    public final boolean isNull(final Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof Double) {
            if (((Double) value).isInfinite() || ((Double) value).isNaN()) {
                return true;
            }
            return false;
        }

        if (value instanceof Float) {
            if (((Float) value).isInfinite() || ((Float) value).isNaN()) {
                return true;
            }
            return false;
        }

        return false;
    }

    public final boolean isPretty() {
        return pretty;
    }

    public final boolean isSkipEmpty() {
        return skipEmpty;
    }

    public final boolean isSkipNulls() {
        return skipNulls;
    }

    public final void reset() {
        this.indent = 0;
        for (int depth = 0, length = state.length; depth < length; depth++) {
            state[depth] = new State(depth);
        }
        this.state[0].reset(Container.DOCUMENT);
    }

    public final void setPretty(final boolean pretty) {
        this.pretty = pretty;
    }

    public final void setSkipEmpty(final boolean skipEmpty) {
        this.skipEmpty = skipEmpty;
    }

    public final void setSkipNulls(final boolean skipNulls) {
        this.skipNulls = skipNulls;
    }

    public final void setWriter(final W writer) {
        this.writer = writer;
    }

    public final JsonWriter<W> withPretty(final boolean pretty) {
        setPretty(pretty);
        return this;
    }

    public final JsonWriter<W> withSkipEmpty(final boolean skipEmpty) {
        setSkipEmpty(skipEmpty);
        return this;
    }

    public final JsonWriter<W> withSkipNulls(final boolean skipNulls) {
        setSkipNulls(skipNulls);
        return this;
    }

    public final JsonWriter<W> withWriter(final W writer) {
        setWriter(writer);
        return this;
    }

    /**
     * Writes the given byte array as a Base64-encoded JSON string value.
     *
     * @param data the byte array to encode, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeBase64String(final byte[] data) throws IOException {
        if (data == null) {
            return writeNull();
        }
        beforeValue();
        writer.write('\"');
        try (final OutputStream os = BaseEncoding.base64()
                .encodingStream(new JsonStringEscapingWriter(writer))) {
            os.write(data);
        }
        writer.write('\"');
        afterValue();
        return this;
    }

    /**
     * Writes the given {@link BigDecimal} as a JSON number value using its plain string
     * representation.
     *
     * @param val the value to write, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public JsonWriter<W> writeBigDecimal(final BigDecimal val) throws IOException {
        if (val == null) {
            return writeNull();
        }
        writeChars(val.toPlainString());
        return this;
    }

    /**
     * Writes the given boolean as a JSON boolean value.
     *
     * @param val the boolean value
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeBoolean(final boolean val) throws IOException {
        writeChars(val ? "true" : "false");
        return this;
    }

    /**
     * Writes the given {@link Boolean} as a JSON boolean value, or a JSON null if the value is
     * {@code null}.
     *
     * @param val the boolean value, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeBoolean(final Boolean val) throws IOException {
        if (val == null) {
            return writeNull();
        }
        writeChars(val.booleanValue() ? "true" : "false");
        return this;
    }

    private final void writeChars(final CharSequence csq) throws IOException {
        beforeValue();
        for (int i = 0, length = csq.length(); i < length; i++) {
            writer.write(csq.charAt(i));
        }
        afterValue();
    }

    /**
     * Writes the given {@link Date} as an ISO-8601 formatted JSON string in UTC.
     *
     * @param val the date value
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeDate(final Date val) throws IOException {
        return writeDate(DateUtils.toZonedDateTimeUtc(val));
    }

    /**
     * Writes the given {@link Instant} as an ISO-8601 formatted JSON string in UTC.
     *
     * @param val the instant value
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeDate(final Instant val) throws IOException {
        return writeDate(DateUtils.toZonedDateTimeUtc(val));
    }

    /**
     * Writes the given {@link ZonedDateTime} as an ISO-8601 formatted JSON string.
     *
     * @param val the zoned date time value
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeDate(final ZonedDateTime val) throws IOException {
        final String iso = DateUtils.toStringIsoFormat(val);
        writeString(iso);
        return this;
    }

    public final JsonWriter<W> writeDouble(final double val) throws IOException {
        if (Double.isInfinite(val) || Double.isNaN(val)) {
            return writeNull();
        }

        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeDouble(final Double val) throws IOException {
        if (val == null || val.isInfinite() || val.isNaN()) {
            return writeNull();
        }

        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    /**
     * Writes the end of the current JSON array.
     *
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeEndArray() throws IOException {
        final boolean notEmpty = this.state[indent--].writeEndArray();
        if (notEmpty) {
            afterValue();
        }
        return this;
    }

    /**
     * Writes the end of the current JSON object.
     *
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeEndObject() throws IOException {
        final boolean notEmpty = this.state[indent--].writeEndObject();
        if (notEmpty) {
            afterValue();
        }
        return this;
    }

    public final JsonWriter<W> writeFloat(final float val) throws IOException {
        if (Float.isInfinite(val) || Float.isNaN(val)) {
            return writeNull();
        }

        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeFloat(final Float val) throws IOException {
        if (val == null || val.isInfinite() || val.isNaN()) {
            return writeNull();
        }

        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    /**
     * Sets the key for the next value to be written within a JSON object.
     *
     * @param key the key name
     * @return this writer for chaining
     */
    public final JsonWriter<W> writeKey(final Object key) {
        this.state[indent].writeKey(key);
        return this;
    }

    /**
     * Writes a key-value pair where the value is written without JSON string escaping.
     *
     * @param key   the key name
     * @param value the value to write unescaped
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeKeyUnescapedValue(final Object key, final Object value)
            throws IOException {
        if (skipEmpty && isEmpty(value) || skipNulls && isNull(value)) {
            return this;
        }
        writeKey(key).writeUnescapedString(value);
        afterValue();
        return this;
    }

    /**
     * Writes a key-value pair within a JSON object.
     *
     * @param key   the key name
     * @param value the value to write
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeKeyValue(final Object key, final Object value) throws IOException {
        if (skipEmpty && isEmpty(value) || skipNulls && isNull(value)) {
            return this;
        }
        writeKey(key).writeObject(value);
        afterValue();
        return this;
    }

    /**
     * Writes the given {@link Collection} as a JSON array.
     *
     * @param collection the collection to write, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeList(final Collection collection) throws IOException {
        if (collection == null) {
            return writeNull();
        }
        if (skipEmpty && collection.size() == 0) {
            return this;
        }

        final Iterator it = collection.iterator();

        writeStartArray();
        while (it.hasNext()) {
            final Object value = it.next();
            if (value == null) {
                writeNull();
            } else {
                writeObject(value);
            }
        }
        writeEndArray();
        return this;
    }

    /**
     * Writes the given array of objects as a JSON array.
     *
     * @param objects the objects to write, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeList(final Object... objects) throws IOException {
        if (objects == null) {
            return writeNull();
        }
        if (skipEmpty && objects.length == 0) {
            return this;
        }

        writeStartArray();
        for (int i = 0; i < objects.length; i++) {
            final Object value = objects[i];
            if (value == null) {
                writeNull();
            } else {
                writeObject(value);
            }
        }
        writeEndArray();
        return this;
    }

    /**
     * Writes the given {@link Map} as a JSON object.
     *
     * @param map the map to write, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeMap(final Map map) throws IOException {
        if (map == null) {
            return writeNull();
        }
        if (skipEmpty && map.size() == 0) {
            return this;
        }

        final Iterator it = map.entrySet().iterator();
        writeStartObject();
        while (it.hasNext()) {
            final Map.Entry entry = (Map.Entry) it.next();
            final Object key = entry.getKey();
            final Object value = entry.getValue();
            writeKeyValue(key, value);
        }
        writeEndObject();
        return this;
    }

    private final JsonWriter<W> writeNewline() throws IOException {
        if (pretty) {
            writer.write("\n");
        }
        return this;
    }

    /**
     * Writes a JSON null value. If skip-nulls or skip-empty is enabled, the null may be suppressed.
     *
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeNull() throws IOException {
        if (skipEmpty || skipNulls) {
            return this;
        }

        beforeValue();
        writer.write("null");
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeNumber(final int val) throws IOException {
        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeNumber(final long val) throws IOException {
        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeNumber(final Number val) throws IOException {
        if (val == null) {
            return writeNull();
        }

        if (val instanceof Double) {
            final Double d = (Double) val;
            return writeDouble(d);
        }

        if (val instanceof Float) {
            final Float f = (Float) val;
            return writeFloat(f);
        }

        if (val instanceof BigDecimal) {
            final BigDecimal bd = (BigDecimal) val;
            return writeBigDecimal(bd);
        }

        writeNumber(val.longValue());
        return this;
    }

    /**
     * Writes the given Java object as the appropriate JSON type, dispatching based on the object's
     * runtime type.
     *
     * @param val the object to write, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeObject(final Object val) throws IOException {
        if (val == null) {
            return writeNull();
        }

        if (val instanceof CharSequence) {
            writeString((CharSequence) val);
            return this;
        }

        if (val instanceof Number) {
            final Number n = (Number) val;
            return writeNumber(n);
        }

        if (val instanceof Boolean) {
            final Boolean b = (Boolean) val;
            return writeBoolean(b);
        }

        if (val instanceof Map) {
            return writeMap((Map) val);
        }

        if (val instanceof Collection) {
            return writeList((Collection) val);
        }

        if (val instanceof Date) {
            final Date d = (Date) val;
            return writeDate(d);
        }
        if (val instanceof Instant) {
            final Instant i = (Instant) val;
            return writeDate(i);
        }
        if (val instanceof ZonedDateTime) {
            final ZonedDateTime zdt = (ZonedDateTime) val;
            return writeDate(zdt);
        }

        if (val instanceof byte[]) {
            return writeBase64String((byte[]) val);
        }

        if (val instanceof Object[]) {
            return writeList((Object[]) val);
        }

        writeString(val.toString());
        return this;
    }

    /**
     * Begins writing a new JSON array.
     *
     * @return this writer for chaining
     */
    public final JsonWriter<W> writeStartArray() {
        this.state[++indent].startArray();
        return this;
    }

    /**
     * Begins writing a new JSON object.
     *
     * @return this writer for chaining
     */
    public final JsonWriter<W> writeStartObject() {
        this.state[++indent].startObject();
        return this;
    }

    /**
     * Writes the given {@link CharSequence} as a properly escaped JSON string value.
     *
     * @param csq the string to write, or {@code null} to write a JSON null
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeString(final CharSequence csq) throws IOException {
        if (csq == null) {
            return writeNull();
        }
        if (skipEmpty && csq.length() == 0) {
            return this;
        }

        beforeValue();
        internalWriteEscapedString(csq);
        afterValue();
        return this;
    }

    /**
     * Writes the given value directly to the output without JSON string escaping. This is useful for
     * writing pre-formatted JSON or raw numeric values.
     *
     * @param val the value to write without escaping
     * @return this writer for chaining
     * @throws IOException if an I/O error occurs
     */
    public final JsonWriter<W> writeUnescapedString(final Object val) throws IOException {
        beforeValue();
        if (val instanceof CharSequence) {
            // we can avoid toString
            final CharSequence csq = (CharSequence) val;
            internalWriteUnescapedString(csq);
        } else {
            final String csq = val != null ? val.toString() : "";
            internalWriteUnescapedString(csq);
        }
        afterValue();
        return this;
    }
}
