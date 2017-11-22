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

import static com.arakelian.json.JsonReader.JsonToken.ARRAY_END;
import static com.arakelian.json.JsonReader.JsonToken.ARRAY_START;
import static com.arakelian.json.JsonReader.JsonToken.BOOLEAN;
import static com.arakelian.json.JsonReader.JsonToken.LONG;
import static com.arakelian.json.JsonReader.JsonToken.NULL;
import static com.arakelian.json.JsonReader.JsonToken.NUMBER;
import static com.arakelian.json.JsonReader.JsonToken.OBJECT_END;
import static com.arakelian.json.JsonReader.JsonToken.OBJECT_START;
import static com.arakelian.json.JsonReader.JsonToken.STRING;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

import com.arakelian.json.JsonReader.JsonToken;

public class JsonReaderTest {
    private Object[] parse(final String json) throws IOException {
        final List<Object> tokens = new ArrayList<>();
        final JsonReader reader = new JsonReader(json);
        for (JsonToken token = reader.nextEvent(); token != JsonToken.EOF; token = reader.nextEvent()) {
            tokens.add(token);
            switch (token) {
            case OBJECT_START:
            case OBJECT_END:
            case ARRAY_END:
            case ARRAY_START:
            case NULL:
            case EOF:
                break;
            case BIGNUMBER:
                tokens.add(reader.getNumberChars().toString());
                break;
            case BOOLEAN:
                tokens.add(reader.getBoolean());
                break;
            case LONG:
                tokens.add(reader.getLong());
                break;
            case NUMBER:
                tokens.add(reader.getNumberChars().toString());
                break;
            case STRING:
                tokens.add(reader.getString());
                break;
            default:
                break;
            }
        }
        return tokens.toArray(new Object[tokens.size()]);
    }

    @Test
    public void testSimple() throws IOException {
        final String json = "" + //
                "{\n" + //
                "   \"one\":1,\n" + //
                "   \"two\":2.5,\n" + //
                "   \"bool1\":false,\n" + //
                "   \"bool2\":true,\n" + //
                "   \"four\":null,\n" + //
                "   \"five\":[\n" + //
                "      \"1\",\n" + //
                "      \"2\",\n" + //
                "      \"3\"\n" + //
                "   ]\n" + //
                "}";
        final Object[] expected = new Object[] { //
                OBJECT_START, //
                STRING, "one", LONG, Long.valueOf(1), //
                STRING, "two", NUMBER, "2.5", //
                STRING, "bool1", BOOLEAN, false, //
                STRING, "bool2", BOOLEAN, true, //
                STRING, "four", NULL, //
                STRING, "five", ARRAY_START, STRING, "1", STRING, "2", STRING, "3", ARRAY_END, //
                OBJECT_END };
        final Object[] actual = parse(json);
        Assert.assertArrayEquals(expected, actual);
    }
}
