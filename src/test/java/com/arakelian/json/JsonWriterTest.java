package com.arakelian.json;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.arakelian.core.utils.DateUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

public class JsonWriterTest {
    @FunctionalInterface
    public interface JsonTest {
        void execute(JsonWriter<StringWriter> writer) throws IOException;
    }

    /** Logger **/
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonWriterTest.class);

    private void assertIllegalStateException(final JsonTest test) throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            Assertions.assertThrows(IllegalStateException.class, () -> {
                test.execute(writer);
                writer.flush();
                LOGGER.info("Supposed to be invalid: {}", sw.toString());
            });
        }
    }

    private String capture(final JsonTest test) throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            test.execute(writer);
        }
        return sw.toString();
    }

    @Test
    public void testBase64() throws IOException {
        final byte[] bytes = new byte[] { 0x01, 0x02, 0x03, 0x04, 0x05, 0x06 };
        assertEquals("\"AQIDBAUG\"", capture(writer -> writer.writeBase64String(bytes)));
    }

    @Test
    public void testDates() throws IOException {
        // 2021-11-18T15:40:00.776000000Z
        final Instant instant = Instant.ofEpochMilli(1637250000776L);
        final ZonedDateTime zdt = DateUtils.toZonedDateTimeUtc(instant);
        assertEquals("1637250000776", capture(writer -> writer.writeObject(instant.toEpochMilli())));
        assertEquals("\"2021-11-18T15:40:00.776000000Z\"", capture(writer -> writer.writeDate(instant)));
        assertEquals("\"2021-11-18T15:40:00.776000000Z\"", capture(writer -> writer.writeDate(zdt)));
        assertEquals("\"2021-11-18T15:40:00.776000000Z\"", capture(writer -> writer.writeObject(zdt)));
    }

    @Test
    public void testDefaults() throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            writeSample(writer);
        }

        Assertions.assertEquals(
                "{\n" //
                        + "  \"string\" : \"string\",\n" //
                        + "  \"emptyString\" : \"\",\n" //
                        + "  \"nullString\" : null,\n" //
                        + "  \"bool\" : true,\n" //
                        + "  \"null\" : null,\n" //
                        + "  \"double\" : 3.141592653589793,\n" //
                        + "  \"nullDouble\" : null,\n" //
                        + "  \"float\" : 3.14,\n" //
                        + "  \"nullFloat\" : null,\n" //
                        + "  \"number\" : 9223372036854775807,\n" //
                        + "  \"nullNumber\" : null,\n" //
                        + "  \"base64\" : \"AQID\",\n" //
                        + "  \"nullBase64\" : null,\n" //
                        + "  \"list\" : [\n" //
                        + "    null,\n" //
                        + "    null,\n" //
                        + "    null,\n" //
                        + "    null,\n" //
                        + "    null,\n" //
                        + "    null,\n" //
                        + "    100\n" //
                        + "  ],\n" //
                        + "  \"emptyList\" : [],\n" //
                        + "  \"nullList\" : null,\n" //
                        + "  \"map\" : {\n" //
                        + "    \"min\" : -9223372036854775808,\n" //
                        + "    \"max\" : 9223372036854775807\n" //
                        + "  },\n" //
                        + "  \"emptyMap\" : {},\n" //
                        + "  \"nullMap\" : null\n" //
                        + "}", //
                sw.toString());
    }

    @Test
    public void testFilterRemovesNullsEmpties() throws IOException {
        final String json = "" + //
                "{\n" + //
                "    \"empty\": \"\",\n" + //
                "    \"id\": 1,\n" + //
                "    \"name\": \"A green door\",\n" + //
                "    \"price\": 12.50,\n" + //
                "    \"tags\": [\"home\", \"green\"],\n" + //
                "        \"zoo\": null\n" + //
                "}";
        final String expected = "" + //
                "{\n" + //
                "  \"name\" : \"A green door\"\n" + //
                "}";

        LOGGER.info("Original JSON: {}", json);
        LOGGER.info("Expected JSON: {}", expected);
        final JsonFilterOptions opts = ImmutableJsonFilterOptions.builder() //
                .addIncludes("empty", "name", "zoo") //
                .build();

        final StringWriter sw = new StringWriter();
        final JsonReader reader = new JsonReader(json);
        final JsonWriter<StringWriter> writer = new JsonWriter<>(sw);
        writer.withSkipNulls(true).withSkipEmpty(true);
        // writer.setPretty(true);
        // execute filter
        final JsonFilter filter = new JsonFilter(reader, writer, opts);
        filter.process();
        final String result = sw.toString();
        LOGGER.info("Processed JSON: {}", result);
        assertEquals(expected, result);
    }

    @Test
    public void testKeyNotAllowed() throws IOException {
        assertIllegalStateException(writer -> {
            writer.writeKey("hello");
        });
    }

    @Test
    public void testMissingKey() throws IOException {
        assertIllegalStateException(writer -> {
            // objects require key value pairs, not standalone objects
            writer.writeStartObject();
            writer.writeNull();
            writer.writeEndObject();
        });
    }

    @Test
    public void testMultipleRootObjects() throws IOException {
        assertIllegalStateException(writer -> {
            writer.writeNull();
            writer.writeNull();
        });
        assertIllegalStateException(writer -> {
            writer.writeString("string");
            writer.writeNull();
        });
        assertIllegalStateException(writer -> {
            writer.writeList(ImmutableList.of());
            writer.writeNull();
        });
    }

    @Test
    public void testNested() throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            writer.setPretty(false);
            writeSampleEmpty(writer);
        }

        Assertions.assertEquals(
                "{{{{{{{{{{\"null\":null,\"emptyString\":\"\",\"emptyList\":[]}}}}}}}}}}", //
                sw.toString());
    }

    @Test
    public void testNestedSkipEmpty() throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            writer.setSkipEmpty(true);
            writeSampleEmpty(writer);
        }

        assertEquals("", sw.toString());
    }

    @Test
    public void testNumbers() throws IOException {
        assertEquals("123", capture(writer -> writer.writeNumber(123)));

        assertEquals("123.456", capture(writer -> writer.writeDouble(123.456d)));
        assertEquals("123.456", capture(writer -> writer.writeDouble(Double.valueOf(123.456d))));
        assertEquals("123.456", capture(writer -> writer.writeObject(123.456d)));
        assertEquals("123.456", capture(writer -> writer.writeNumber(123.456f)));

        assertEquals("123.456", capture(writer -> writer.writeFloat(123.456f)));
        assertEquals("123.456", capture(writer -> writer.writeFloat(Float.valueOf(123.456f))));

        final String bd = "123456789012345678901234567890.12345678901234567890";
        assertEquals(bd, capture(writer -> writer.writeBigDecimal(new BigDecimal(bd))));
        assertEquals(bd, capture(writer -> writer.writeNumber(new BigDecimal(bd))));
        assertEquals(bd, capture(writer -> writer.writeObject(new BigDecimal(bd))));
    }

    @Test
    public void testSkipEmpty() throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            writer.setSkipEmpty(true);
            writeSample(writer);
        }

        assertEquals(
                "{\n" //
                        + "  \"string\" : \"string\",\n" //
                        + "  \"bool\" : true,\n" //
                        + "  \"double\" : 3.141592653589793,\n" //
                        + "  \"float\" : 3.14,\n" //
                        + "  \"number\" : 9223372036854775807,\n" //
                        + "  \"base64\" : \"AQID\",\n" //
                        + "  \"list\" : [\n" //
                        + "    100\n" //
                        + "  ],\n" //
                        + "  \"map\" : {\n" //
                        + "    \"min\" : -9223372036854775808,\n" //
                        + "    \"max\" : 9223372036854775807\n" //
                        + "  }\n" //
                        + "}", //
                sw.toString());
    }

    @Test
    public void testSkipNulls() throws IOException {
        final StringWriter sw = new StringWriter();
        try (JsonWriter<StringWriter> writer = new JsonWriter<>(sw)) {
            writer.setSkipNulls(true);
            writeSample(writer);
        }

        assertEquals(
                "{\n" //
                        + "  \"string\" : \"string\",\n" //
                        + "  \"emptyString\" : \"\",\n" //
                        + "  \"bool\" : true,\n" //
                        + "  \"double\" : 3.141592653589793,\n" //
                        + "  \"float\" : 3.14,\n" //
                        + "  \"number\" : 9223372036854775807,\n" //
                        + "  \"base64\" : \"AQID\",\n" //
                        + "  \"list\" : [\n" //
                        + "    100\n" //
                        + "  ],\n" //
                        + "  \"emptyList\" : [],\n" //
                        + "  \"map\" : {\n" //
                        + "    \"min\" : -9223372036854775808,\n" //
                        + "    \"max\" : 9223372036854775807\n" //
                        + "  },\n" //
                        + "  \"emptyMap\" : {}\n" //
                        + "}", //
                sw.toString());
    }

    private void writeSample(final JsonWriter<StringWriter> writer) throws IOException {
        writer.writeStartObject();

        writer.writeKey("string");
        writer.writeString("string");

        writer.writeKey("emptyString");
        writer.writeString("");

        writer.writeKey("nullString");
        writer.writeString(null);

        writer.writeKey("bool");
        writer.writeBoolean(true);

        writer.writeKey("null");
        writer.writeNull();

        writer.writeKey("double");
        writer.writeDouble(Double.valueOf(Math.PI));

        writer.writeKey("nullDouble");
        writer.writeDouble(null);

        writer.writeKey("float");
        writer.writeFloat(Float.valueOf(3.14f));

        writer.writeKey("nullFloat");
        writer.writeFloat(null);

        writer.writeKey("number");
        writer.writeNumber(Long.MAX_VALUE);

        writer.writeKey("nullNumber");
        writer.writeNumber(null);

        writer.writeKey("base64");
        writer.writeBase64String(new byte[] { 1, 2, 3 });

        writer.writeKey("nullBase64");
        writer.writeBase64String(null);

        writer.writeKey("list");
        writer.writeList(
                Lists.newArrayList(
                        Float.NaN,
                        Float.NEGATIVE_INFINITY,
                        Float.POSITIVE_INFINITY,
                        Double.NaN,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        100));

        writer.writeKey("emptyList");
        writer.writeList(ImmutableList.of());

        writer.writeKey("nullList");
        writer.writeList((List) null);

        writer.writeKey("map");
        writer.writeMap(ImmutableMap.of("min", Long.MIN_VALUE, "max", Long.MAX_VALUE));

        writer.writeKey("emptyMap");
        writer.writeMap(ImmutableMap.of());

        writer.writeKey("nullMap");
        writer.writeMap((Map) null);

        writer.writeEndObject();
    }

    private void writeSampleEmpty(final JsonWriter<StringWriter> writer) throws IOException {
        for (int i = 0; i < 10; i++) {
            writer.writeStartObject();
        }
        writer.writeKeyValue("null", null);
        writer.writeKeyValue("emptyString", "");
        writer.writeKeyValue("emptyList", ImmutableList.of());
        for (int i = 0; i < 10; i++) {
            writer.writeEndObject();
        }
    }
}
