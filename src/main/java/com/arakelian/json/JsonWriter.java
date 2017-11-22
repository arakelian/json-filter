/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.common.io.BaseEncoding;

/**
 * Fast JSON writer
 */
public class JsonWriter<W extends Writer> implements Closeable {
    private static enum CommaState {
        BEFORE_FIRST, AFTER_KEY, AFTER_VALUE, AFTER_COMMA;
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
     *            list of alternating key-value pairs
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

    private final CommaState[] commaState = new CommaState[32];

    /** True to skip null values **/
    private boolean skipNulls = false;

    /** True to skip empty values **/
    private boolean skipEmpty = false;

    public JsonWriter() {
        reset();
    }

    public JsonWriter(final W writer) {
        setWriter(writer);
        reset();
    }

    private final JsonWriter<W> afterValue() {
        this.commaState[indent] = CommaState.AFTER_VALUE;
        return this;
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

    private void indent() throws IOException {
        if (pretty) {
            for (int i = 0; i < indent; i++) {
                writer.write("  ");
            }
        }
    }

    private final void internalWriteEscapedChar(final char ch) throws IOException {
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
        case '/':
            writer.write("\\/");
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

    private final void internalWriteEscapedString(final CharSequence csq) throws IOException {
        writer.write('\"');

        for (int i = 0, len = csq != null ? csq.length() : 0; i < len; i++) {
            final char ch = csq.charAt(i);
            internalWriteEscapedChar(ch);
        } // for

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

    private final void nextLine() throws IOException {
        writeNewline();
        indent();
    }

    public final void reset() {
        this.indent = 0;
        this.commaState[indent] = CommaState.BEFORE_FIRST;
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

    public final JsonWriter<W> writeBase64String(final byte[] data) throws IOException {
        if (data == null) {
            return writeNull();
        }
        writeCommaBetweenValues();
        writer.write('\"');
        try (final OutputStream os = BaseEncoding.base64()
                .encodingStream(new JsonStringEscapingWriter(writer))) {
            os.write(data);
        }
        writer.write('\"');
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeBoolean(final boolean val) throws IOException {
        writeChars(val ? "true" : "false");
        return this;
    }

    private final void writeChars(final CharSequence csq) throws IOException {
        writeCommaBetweenValues();
        for (int i = 0, length = csq.length(); i < length; i++) {
            writer.write(csq.charAt(i));
        }
        afterValue();
    }

    /**
     * Commas are automatically inserted and therefore this method is private
     **/
    private final JsonWriter<W> writeComma() throws IOException {
        writer.write(',');
        nextLine();
        this.commaState[indent] = CommaState.AFTER_VALUE;
        return this;
    }

    /** Determines if comma is needed here and automatically inserts one **/
    private final void writeCommaBetweenValues() throws IOException {
        final CommaState state = this.commaState[indent];
        if (state == CommaState.AFTER_VALUE) {
            writeComma();
        }
    }

    public final JsonWriter<W> writeDouble(final Double val) throws IOException {
        if (val.isInfinite() || val.isNaN()) {
            return writeNull();
        }
        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeEndArray() throws IOException {
        this.commaState[--indent] = CommaState.AFTER_VALUE;
        nextLine();
        writer.write(']');
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeEndObject() throws IOException {
        this.commaState[--indent] = CommaState.AFTER_VALUE;
        nextLine();
        writer.write('}');
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeFloat(final Float val) throws IOException {
        if (val.isInfinite() || val.isNaN()) {
            return writeNull();
        }
        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeKey(final Object key) throws IOException {
        writeCommaBetweenValues();
        if (key instanceof CharSequence) {
            internalWriteEscapedString((CharSequence) key);
        } else {
            internalWriteEscapedString(String.valueOf(key));
        }
        if (pretty) {
            // space before colon matches Jackson
            writer.write(" ");
        }
        writer.write(":");
        if (pretty) {
            writer.write(" ");
        }
        this.commaState[indent] = CommaState.AFTER_KEY;
        return this;
    }

    public final JsonWriter<W> writeKeyUnescapedValue(final Object key, final Object value)
            throws IOException {
        if (skipEmpty && isEmpty(value) || skipNulls && isNull(value)) {
            return this;
        }
        writeKey(key).writeUnescapedString(value);
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeKeyValue(final Object key, final Object value) throws IOException {
        if (skipEmpty && isEmpty(value) || skipNulls && isNull(value)) {
            return this;
        }
        writeKey(key).writeObject(value);
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeList(final Collection collection) throws IOException {
        if (collection == null) {
            return writeNull();
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

    public final JsonWriter<W> writeList(final Object... objects) throws IOException {
        if (objects == null) {
            return writeNull();
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

    public final JsonWriter<W> writeMap(final Map map) throws IOException {
        if (map == null) {
            return writeNull();
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

    public final JsonWriter<W> writeNull() throws IOException {
        writeCommaBetweenValues();
        writer.write("null");
        afterValue();
        return this;
    }

    public final JsonWriter<W> writeNumber(final long val) throws IOException {
        final StringBuilder buf = new StringBuilder();
        buf.append(val);
        writeChars(buf);
        return this;
    }

    public final JsonWriter<W> writeNumber(final Number val) throws IOException {
        if (val instanceof Double) {
            final Double d = (Double) val;
            return writeDouble(d);
        }

        if (val instanceof Float) {
            final Float f = (Float) val;
            return writeFloat(f);
        }

        writeNumber(val.longValue());
        return this;
    }

    public final JsonWriter<W> writeObject(final Object value) throws IOException {
        if (value == null) {
            return writeNull();
        }

        if (value instanceof CharSequence) {
            writeString((CharSequence) value);
            return this;
        }

        if (value instanceof Number) {
            final Number val = (Number) value;
            return writeNumber(val);
        }

        if (value instanceof Boolean) {
            final boolean val = ((Boolean) value).booleanValue();
            return writeBoolean(val);
        }

        if (value instanceof Map) {
            return writeMap((Map) value);
        }

        if (value instanceof Collection) {
            return writeList((Collection) value);
        }

        if (value instanceof byte[]) {
            return writeBase64String((byte[]) value);
        }

        if (value instanceof Object[]) {
            return writeList((Object[]) value);
        }

        writeString(value.toString());
        return this;
    }

    public final JsonWriter<W> writeStartArray() throws IOException {
        writeCommaBetweenValues();
        this.commaState[++indent] = CommaState.BEFORE_FIRST;
        writer.write('[');
        nextLine();
        return this;
    }

    public final JsonWriter<W> writeStartObject() throws IOException {
        writeCommaBetweenValues();
        this.commaState[++indent] = CommaState.BEFORE_FIRST;
        writer.write('{');
        nextLine();
        return this;
    }

    public final void writeString(final CharSequence csq) throws IOException {
        writeCommaBetweenValues();
        internalWriteEscapedString(csq);
        afterValue();
    }

    public final JsonWriter<W> writeUnescapedString(final Object key) throws IOException {
        writeCommaBetweenValues();
        if (key instanceof CharSequence) {
            // we can avoid toString
            final CharSequence csq = (CharSequence) key;
            internalWriteUnescapedString(csq);
        } else {
            final String csq = key != null ? key.toString() : "";
            internalWriteUnescapedString(csq);
        }
        afterValue();
        return this;
    }
}
