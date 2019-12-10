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

import java.io.IOException;
import java.io.StringWriter;
import java.util.Set;
import java.util.function.Predicate;

import com.arakelian.json.JsonReader.JsonToken;
import com.google.common.base.Preconditions;

/**
 * Performs high-speed filtering of a JSON document. This class uses a highly optimized streaming
 * JSON reader and writer to prevent unnecessary memory allocations.
 *
 * This class is particularly useful for redacting a JSON document (for security purposes), as well
 * as for producing smaller JSON documents prior to deserialization using Jackson or other library,
 * where only a small number of fields may actually be used.
 */
public class JsonFilter {
    private static final class PathPredicate implements Predicate<CharSequence> {
        /** Path patterns which are included **/
        private final Set<String> includes;

        /** Path patterns which are excluded **/
        private final Set<String> excludes;

        private PathPredicate(final Set<String> includes, final Set<String> excludes) {
            this.includes = includes;
            this.excludes = excludes;
        }

        @Override
        public boolean test(final CharSequence path) {
            // excludes are always processed first!
            if (excludes != null && excludes.size() != 0) {
                for (final String exclude : excludes) {
                    if (pathStartsWith(path, exclude, true)) {
                        return false;
                    }
                }
            }

            // include when specifically asked to do so
            if (includes != null && includes.size() != 0) {
                for (final String include : includes) {
                    if (pathStartsWith(path, include, false)) {
                        return true;
                    }
                }
            }

            // if client specified which fields to include, it must be on that
            // list; otherwise, everything included by default
            return includes == null || includes.size() == 0;
        }

        private boolean pathStartsWith(
                final CharSequence path,
                final CharSequence prefix,
                final boolean excluding) {
            if (path == prefix) {
                return true;
            }
            if (path == null || prefix == null) {
                return false;
            }

            final int prefixLength;
            final int prefixStart;
            if (prefix.length() > 0 && prefix.charAt(0) == '/') {
                // ignore leading slash
                prefixStart = 1;
                prefixLength = prefix.length() - 1;
            } else {
                prefixStart = 0;
                prefixLength = prefix.length();
            }

            // compare as much as we can
            final int pathLength = path.length();
            final int length = Math.min(pathLength, prefixLength);
            for (int i = 0; i < length; ++i) {
                if (path.charAt(i) != prefix.charAt(prefixStart + i)) {
                    return false;
                }
            }

            if (pathLength == prefixLength) {
                // exact match
                return true;
            }

            if (pathLength > prefixLength) {
                // path must start with prefix + "/"
                return path.charAt(prefixLength) == JsonFilter.PATH_SEPARATOR;
            } else {
                // need to go deeper for a match
                return prefix.charAt(pathLength) == JsonFilter.PATH_SEPARATOR ? !excluding : false;
            }
        }
    }

    /** Path separator when JSON is nested **/
    public static final char PATH_SEPARATOR = '/';

    /**
     * Returns the compact version of the given JSON string
     *
     * @param s
     *            input string
     * @return compact version of the given JSON string
     * @throws IOException
     *             if invalid JSON
     */
    public static CharSequence compact(final CharSequence s) throws IOException {
        return identityTransform(s, false);
    }

    /**
     * Returns the compact version of the given JSON string; if the input string cannot be parsed
     * for whatever reason, the original input value is returned as-is
     *
     * @param s
     *            input string
     * @return compact version of the given string or original string if invalid JSON
     */
    public static CharSequence compactQuietly(final CharSequence s) {
        return identityTransformQuietly(s, false);
    }

    private static boolean equals(final CharSequence lhs, final CharSequence rhs) {
        final int l1 = lhs.length();
        int l2 = rhs.length();
        if (l1 != l2) {
            return false;
        }

        int i1 = 0;
        int i2 = 0;
        while (l2-- > 0) {
            final char c1 = lhs.charAt(i1++);
            final char c2 = rhs.charAt(i2++);
            if (c1 != c2) {
                return false;
            }
        }

        return true;
    }

    public static CharSequence filter(final CharSequence json, final JsonFilterOptions options)
            throws IOException {
        if (json == null || json.length() == 0) {
            return json;
        }

        if (options == null || options.isEmpty() && !options.isIdentityTransform()) {
            return json;
        }

        final StringWriter sw = new StringWriter();
        try (final JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            writer.setPretty(options.getPretty().orElse(Boolean.FALSE).booleanValue());
            final JsonFilter filter = new JsonFilter(new JsonReader(json), writer, options);
            filter.process();
        }

        final StringBuffer buf = sw.getBuffer();
        final boolean unchanged = equals(buf, json);
        if (unchanged) {
            // do not needlessly allocate new String if they're identical
            return json;
        }
        return buf.toString();
    }

    private static CharSequence identityTransform(final CharSequence s, final boolean pretty)
            throws IOException {
        if (isJsonObjectOrArray(s)) {
            final JsonFilterOptions opts = ImmutableJsonFilterOptions.builder() //
                    .identityTransform(true) //
                    .pretty(pretty) //
                    .build();
            return filter(s, opts);
        }
        return s;
    }

    private static CharSequence identityTransformQuietly(final CharSequence s, final boolean pretty) {
        try {
            return identityTransform(s, pretty);
        } catch (final IOException e) {
            // return original string
            return s;
        }
    }

    private static boolean isJsonObjectOrArray(final CharSequence s) {
        if (s == null || s.length() == 0) {
            return false;
        }
        final int length = s.length();
        int end = length;
        int start = 0;

        while (start < end && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        while (start < end && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        final int trimmedLength = end - start;
        if (trimmedLength < 2) {
            return false;
        }
        final char first = s.charAt(start);
        final char last = s.charAt(end - 1);
        return first == '{' && last == '}' || first == '[' && last == ']';
    }

    /**
     * Returns the pretty version of the given string
     *
     * @param str
     *            input string
     * @return pretty version of the given string
     * @throws IOException
     *             if invalid JSON
     */
    public static CharSequence prettyify(final CharSequence str) throws IOException {
        return identityTransform(str, true);
    }

    /**
     * Returns the pretty version of the given string; if the input string cannot be parsed for
     * whatever reason, the original input value is returned as-is
     *
     * @param str
     *            input string
     * @return pretty version of the given string or the original string if invalid JSON
     */
    public static CharSequence prettyifyQuietly(final CharSequence str) {
        return identityTransformQuietly(str, true);
    }

    /**
     * Filtering options
     */
    private final JsonFilterOptions options;

    /**
     * The field path in the input document that we are currently processing
     **/
    private final StringBuilder currentPath;

    /**
     * Predicate that indicates if a particular field path should be included in the output.
     */
    private final Predicate<CharSequence> predicate;

    /**
     * Flag that indicates we are processing a section of the input JSON document that will not be
     * copied to the output.
     */
    private boolean skipping;

    /**
     * Current depth
     */
    private int depth;

    /**
     * Low-level JSON reader that does not needlessly produce Strings and other Java objects as the
     * data is read.
     **/
    private final JsonReader reader;

    /**
     * Low-level JSON writer that does not needlessly produce Strings and other Java objects as JSON
     * is produced.
     */
    private final JsonWriter writer;

    public JsonFilter(final JsonReader reader, final JsonWriter writer, final JsonFilterOptions options) {
        Preconditions.checkArgument(reader != null, "reader must be non-null");
        Preconditions.checkArgument(writer != null, "writer must be non-null");
        Preconditions.checkArgument(options != null, "options must be non-null");
        this.reader = reader;
        this.writer = writer;
        this.options = options;
        this.currentPath = new StringBuilder();
        this.predicate = new PathPredicate(options.getIncludes(), options.getExcludes());
    }

    public CharSequence getCurrentPath() {
        return currentPath;
    }

    public final int getDepth() {
        return depth;
    }

    public final JsonFilterOptions getOptions() {
        return options;
    }

    public final JsonWriter getWriter() {
        return writer;
    }

    public JsonFilter process() throws IOException {
        depth = 0;
        JsonToken token = reader.nextEvent();
        switch (token) {
        case OBJECT_START:
            processStartObject();
            break;
        case ARRAY_START:
            processStartArray();
            break;
        default:
            throw new IOException("Expected start of object or array, but encountered: " + token);
        }

        token = reader.nextEvent();
        if (token != JsonToken.EOF) {
            throw new IOException("Expected end of input");
        }
        return this;
    }

    private void processArrayValues() throws IOException {
        for (;;) {
            final JsonToken token = reader.nextEvent();
            switch (token) {
            case ARRAY_END:
                if (!skipping) {
                    writer.writeEndArray();
                }
                return;
            case ARRAY_START:
                processStartArray();
                break;
            case OBJECT_START:
                processStartObject();
                break;
            default:
                processValue();
            }
        }
    }

    private void processObjectValues() throws IOException {
        final boolean lastSkipping = skipping;
        for (;;) {
            final JsonToken token = reader.nextEvent();
            if (token == JsonToken.OBJECT_END) {
                if (!skipping) {
                    final JsonFilterCallback callback = options.getCallback();
                    if (callback != null) {
                        callback.beforeEndObject(this);
                    }
                    writer.writeEndObject();
                }
                depth--;
                return;
            }

            if (token != JsonToken.STRING) {
                throw new IOException("Expected object key, but encountered: " + token);
            }

            if (skipping) {
                // advance to value, ignoring the key
                reader.nextEvent();

                // skip value, which may be nested structure itself
                processValue();
                continue;
            }

            final CharSequence name = reader.getStringChars();
            final int lastPath = pushPath(name);
            try {
                if (predicate.test(currentPath)) {
                    // must write name before advancing reader
                    writer.writeKey(name);
                } else {
                    skipping = true;
                }

                // advance reader to value
                reader.nextEvent();

                // process value
                processValue();
            } finally {
                currentPath.setLength(lastPath);
                skipping = lastSkipping;
            }
        }
    }

    private void processStartArray() throws IOException {
        if (!skipping) {
            writer.writeStartArray();
        }
        processArrayValues();
    }

    private void processStartObject() throws IOException {
        depth++;
        if (!skipping) {
            final JsonFilterCallback callback = options.getCallback();
            if (callback != null) {
                callback.afterStartObject(this);
            }
            writer.writeStartObject();
        }
        processObjectValues();
    }

    private void processValue() throws IOException {
        final JsonToken token = reader.lastEvent();
        switch (token) {
        case NULL:
            if (!skipping) {
                writer.writeNull();
            }
            break;
        case BIGNUMBER:
            if (!skipping) {
                writer.writeUnescapedString(reader.getNumberChars());
            }
            break;
        case BOOLEAN:
            if (!skipping) {
                writer.writeBoolean(reader.getBoolean());
            }
            break;
        case LONG:
            if (!skipping) {
                writer.writeNumber(reader.getLong());
            }
            break;
        case NUMBER:
            if (!skipping) {
                writer.writeUnescapedString(reader.getNumberChars());
            }
            break;
        case STRING:
            if (!skipping) {
                writer.writeString(reader.getStringChars());
            }
            break;
        case ARRAY_START:
            processStartArray();
            break;
        case OBJECT_START:
            processStartObject();
            break;
        case OBJECT_END:
        case ARRAY_END:
        case EOF:
        default:
            throw new IOException("Expecting value but encountered: " + token);
        }
    }

    private int pushPath(final CharSequence csq) {
        final int mark = currentPath.length();
        if (mark != 0) {
            currentPath.append('/');
        }
        currentPath.append(csq);
        return mark;
    }
}
