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

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

import com.arakelian.json.JsonFilter;

import com.arakelian.json.ImmutableJsonFilterOptions;

public class JsonFilterTest {
	@Test
	public void testExcludeComplex() throws IOException {
		final String json = "" + //
				"{\n" + //
				"    \"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" + //
				"    \"title\": \"Product\",\n" + //
				"    \"description\": \"A product from Acme's catalog\",\n" + //
				"    \"type\": \"object\",\n" + //
				"    \"properties\": {\n" + //
				"        \"id\": {\n" + //
				"            \"description\": \"The unique identifier for a product\",\n" + //
				"            \"type\": \"integer\"\n" + //
				"        }\n" + //
				"    },\n" + //
				"    \"required\": [\"id\"]\n" + //
				"}";
		final String expected = "" + //
				"{\n" + //
				"  \"$schema\" : \"http:\\/\\/json-schema.org\\/draft-04\\/schema#\",\n" + //
				"  \"title\" : \"Product\",\n" + //
				"  \"description\" : \"A product from Acme's catalog\",\n" + //
				"  \"type\" : \"object\",\n" + //
				"  \"properties\" : {\n" + //
				"    \"id\" : {\n" + //
				"      \"type\" : \"integer\"\n" + //
				"    }\n" + //
				"  },\n" + //
				"  \"required\" : [\n" + //
				"    \"id\"\n" + //
				"  ]\n" + //
				"}";
		Assert.assertEquals(expected,
				JsonFilter.filter(json, //
						ImmutableJsonFilterOptions.builder() //
								.addExcludes("properties/id/description") //
								.pretty(true) //
								.build()));
	}

	@Test
	public void testExcludeSimple() throws IOException {
		final String json = "" + //
				"{\n" + //
				"    \"id\": 1,\n" + //
				"    \"name\": \"A green door\",\n" + //
				"    \"created\": \"2016-12-21T16:46:39.000Z\",\n" + //
				"    \"updated\": \"2016-12-21T16:46:39.000Z\"\n" + //
				"}";
		final String expected = "" + //
				"{\n" + //
				"  \"id\" : 1,\n" + //
				"  \"name\" : \"A green door\"\n" + //
				"}";
		Assert.assertEquals(expected,
				JsonFilter.filter(json, //
						ImmutableJsonFilterOptions.builder() //
								.addExcludes("created", "updated") //
								.pretty(true) //
								.build()));
	}

	@Test
	public void testIncludeComplex() throws IOException {
		final String json = "" + //
				"{\n" + //
				"    \"$schema\": \"http://json-schema.org/draft-04/schema#\",\n" + //
				"    \"title\": \"Product\",\n" + //
				"    \"description\": \"A product from Acme's catalog\",\n" + //
				"    \"type\": \"object\",\n" + //
				"    \"properties\": {\n" + //
				"        \"id\": {\n" + //
				"            \"description\": \"The unique identifier for a product\",\n" + //
				"            \"type\": \"integer\"\n" + //
				"        }\n" + //
				"    },\n" + //
				"    \"required\": [\"id\"]\n" + //
				"}";
		final String expected = "{\n" + //
				"  \"properties\" : {\n" + //
				"    \"id\" : {\n" + //
				"      \"description\" : \"The unique identifier for a product\",\n" + //
				"      \"type\" : \"integer\"\n" + //
				"    }\n" + //
				"  }\n" + //
				"}";
		Assert.assertEquals(expected,
				JsonFilter.filter(json,
						ImmutableJsonFilterOptions.builder() //
								.addIncludes("properties/id") //
								.pretty(true) //
								.build()));
	}

	@Test
	public void testIncludeSimple() throws IOException {
		final String json = "" + //
				"{\n" + //
				"    \"id\": 1,\n" + //
				"    \"name\": \"A green door\",\n" + //
				"    \"price\": 12.50,\n" + //
				"    \"tags\": [\"home\", \"green\"]\n" + //
				"}";
		final String expected = "" + //
				"{\n" + //
				"  \"name\" : \"A green door\"\n" + //
				"}";
		Assert.assertEquals(expected,
				JsonFilter.filter(json,
						ImmutableJsonFilterOptions.builder() //
								.addIncludes("name") //
								.pretty(true) //
								.build()));
	}

	@Test
	public void testPrettyArrays() {
		// ignore invalid json
		assertEquals("[blah]", JsonFilter.prettyifyQuietly("[blah]"));
		assertEquals("[blah]", JsonFilter.compactQuietly("[blah]"));
		assertEquals("[\"a\",\"b\"][\"c\":\"d\"]", JsonFilter.prettyifyQuietly("[\"a\",\"b\"][\"c\":\"d\"]"));
		assertEquals("[\"a\",\"b\"][\"c\":\"d\"]", JsonFilter.compactQuietly("[\"a\",\"b\"][\"c\":\"d\"]"));

		// make it pretty
		final String prettyify = JsonFilter.prettyifyQuietly("\n\n[1,2,\"3\"\n,\nfalse ]\n\n");
		assertEquals("[\n" + //
				"  1,\n" + //
				"  2,\n" + //
				"  \"3\",\n" + //
				"  false\n" + //
				"]", prettyify);

		// make it pretty
		final String compact = JsonFilter.compactQuietly(prettyify);
		assertEquals("[1,2,\"3\",false]", compact);
	}

	@Test
	public void testPrettyObjects() {
		// ignore invalid json
		assertEquals("{blah}", JsonFilter.prettyifyQuietly("{blah}"));
		assertEquals("{blah}", JsonFilter.compactQuietly("{blah}"));
		assertEquals("{\"a\":1}{\"b\":2}", JsonFilter.prettyifyQuietly("{\"a\":1}{\"b\":2}"));
		assertEquals("{\"a\":1}{\"b\":2}", JsonFilter.compactQuietly("{\"a\":1}{\"b\":2}"));

		// make it pretty
		final String prettyify = JsonFilter
				.prettyifyQuietly("\n\n{\"id\":\"100\",\"name\":\"Greg Arakelian\"}\n\n");
		assertEquals("{\n" + //
				"  \"id\" : \"100\",\n" + //
				"  \"name\" : \"Greg Arakelian\"\n" + //
				"}", prettyify);

		// make it pretty
		final String compact = JsonFilter.compactQuietly(prettyify);
		assertEquals("{\"id\":\"100\",\"name\":\"Greg Arakelian\"}", compact);
	}
}
