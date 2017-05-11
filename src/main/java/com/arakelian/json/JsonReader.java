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

import java.io.IOException;
import java.io.Reader;

public final class JsonReader {
	public static class JsonParseException extends IOException {
		public JsonParseException(final String msg) {
			super(msg);
		}
	}

	public enum JsonToken {
		// Event indicating a JSON string value, including member names of objects
		STRING,

		// Event indicating a JSON number value which fits into a signed 64 bit integer
		LONG,

		/**
		 * Event indicating a JSON number value which has a fractional part or an exponent and with
		 * string length &amp;= 23 chars not including sign. This covers all representations of normal
		 * values for Double.toString().
		 */
		NUMBER,

		/**
		 * Event indicating a JSON number value that was not produced by toString of any Java
		 * primitive numerics such as Double or Long. It is either an integer outside the range of a
		 * 64 bit signed integer, or a floating point value with a string representation of more
		 * than 23 chars.
		 */
		BIGNUMBER,

		// Event indicating a JSON boolean
		BOOLEAN,

		// Event indicating a JSON null
		NULL,

		// Event indicating the start of a JSON object
		OBJECT_START,

		// Event indicating the end of a JSON object
		OBJECT_END,

		// Event indicating the start of a JSON array
		ARRAY_START,

		// Event indicating the end of a JSON array
		ARRAY_END,

		// Event indicating the end of input has been reached
		EOF;
	}

	enum ParserState {
		DID_OBJSTART, // '{' just read
		DID_ARRSTART, // '[' just read
		DID_ARRELEM, // array element just read
		DID_MEMNAME, // object member name (map key) just read
		DID_MEMVAL; // object member value (map val) just read
	}

	private static final SimpleCharArr NULL_OUTPUT = new NullCharArr();

	// set 1 bit so 0xA0 will be flagged as whitespace
	private static final long WS_MASK = 1L << ' ' | 1L << '\t' | 1L << '\r' | 1L << '\n' | 1L << '#'
			| 1L << '/' | 0x01;

	/** {@link #numberState} flag, '.' already read **/
	private static final int HAS_FRACTION = 0x01;

	/** {@link #numberState} flag, '[eE][+-]?[0-9]' already read **/
	private static final int HAS_EXPONENT = 0x02;

	private static final char[] TRUE_CHARS = new char[] { 't', 'r', 'u', 'e' };

	private static final char[] FALSE_CHARS = new char[] { 'f', 'a', 'l', 's', 'e' };

	private static final char[] NULL_CHARS = new char[] { 'n', 'u', 'l', 'l' };

	/** input buffer with JSON text in it **/
	final char[] buf;

	/** current position in the buffer **/
	int start;

	/** end position in the buffer (one past last valid index) **/
	int end;

	/** optional reader to obtain data from **/
	final Reader in;

	/** true if the end of the stream was reached. **/
	boolean eof;

	/** global position = gpos + start **/
	long gpos;

	/** last event read **/
	JsonToken event;

	/** temporary output buffer **/
	private final SimpleCharArr out = new SimpleCharArr(64);

	/** a dummy buffer we can use to point at other buffers **/
	private final SimpleCharArr tmp = new SimpleCharArr(null, 0, 0);

	// We need to keep some state in order to (at a minimum) know if
	// we should skip ',' or ':'.
	private ParserState[] stack = new ParserState[16];

	/** pointer into the stack of parser states **/
	private int level;

	/** current parser state **/
	private ParserState state;

	/** info about value that was just read (or is in the middle of being read) **/
	private JsonToken valueState;

	/** boolean value read **/
	private boolean lastBoolean;

	/** long value read **/
	private long lastLong;

	/** current state while reading a number **/
	private int numberState;

	public JsonReader(final char[] data, final int start, final int end) {
		this.in = null;
		this.buf = data;
		this.start = start;
		this.end = end;
	}

	public JsonReader(final Reader in) {
		// 8192 matches the default buffer size of a BufferedReader so double
		// buffering of the data is avoided.
		this(in, new char[8192]);
	}

	public JsonReader(final Reader in, final char[] buffer) {
		this.in = in;
		this.buf = buffer;
	}

	public JsonReader(final String data) {
		this(data, 0, data.length());
	}

	public JsonReader(final String data, final int start, final int end) {
		this.in = null;
		this.start = start;
		this.end = end;
		this.buf = new char[end - start];
		data.getChars(start, end, buf, 0);
	}

	private void copyBigNumber(final SimpleCharArr dest) throws IOException {
		if (dest != out) {
			dest.write(out);
		}

		if ((numberState & HAS_EXPONENT) != 0) {
			copyExpDigits(dest, Integer.MAX_VALUE);
			return;
		}
		if (numberState != 0) {
			copyFrac(dest, Integer.MAX_VALUE);
			return;
		}

		for (;;) {
			final int ch = readChar();
			if (ch >= '0' && ch <= '9') {
				dest.write(ch);
			} else if (ch == '.') {
				dest.write(ch);
				copyFrac(dest, Integer.MAX_VALUE);
				return;
			} else if (ch == 'e' || ch == 'E') {
				dest.write(ch);
				copyExp(dest, Integer.MAX_VALUE);
				return;
			} else {
				if (ch != -1) {
					start--;
				}
				return;
			}
		}
	}

	private JsonToken copyExp(final SimpleCharArr dest, int limit) throws IOException {
		// call after 'e' or 'E' has been seen to read the rest of the exponent
		numberState |= HAS_EXPONENT;
		int ch = readChar();
		limit--;

		if (ch == '+' || ch == '-') {
			dest.write(ch);
			ch = readChar();
			limit--;
		}

		// make sure at least one digit is read.
		if (ch < '0' || ch > '9') {
			throw createParseException("missing exponent number");
		}
		dest.write(ch);

		return copyExpDigits(dest, limit);
	}

	private JsonToken copyExpDigits(final SimpleCharArr dest, int limit) throws IOException {
		// continuation of readExpStart
		while (--limit >= 0) {
			final int ch = readChar();
			if (ch >= '0' && ch <= '9') {
				dest.write(ch);
			} else {
				if (ch != -1) {
					start--; // back up
				}
				return JsonToken.NUMBER;
			}
		}
		return JsonToken.BIGNUMBER;
	}

	private JsonToken copyFrac(final SimpleCharArr dest, int limit) throws IOException {
		// read digits right of decimal point
		numberState = HAS_FRACTION; // deliberate set instead of '|'
		while (--limit >= 0) {
			final int ch = readChar();
			if (ch >= '0' && ch <= '9') {
				dest.write(ch);
			} else if (ch == 'e' || ch == 'E') {
				dest.write(ch);
				return copyExp(dest, limit);
			} else {
				if (ch != -1) {
					start--; // back up
				}
				return JsonToken.NUMBER;
			}
		}
		return JsonToken.BIGNUMBER;
	}

	private void copyStringChars(final SimpleCharArr dest, int middle) throws IOException {
		// middle is the pointer to the middle of a buffer to start scanning for a non-string
		// character ('"' or "/"). start<=middle<end
		// this should be faster for strings with fewer escapes, but probably slower for many
		// escapes.
		for (;;) {
			if (middle >= end) {
				dest.write(buf, start, middle - start);
				start = middle;
				fillExpectingMore();
				middle = start;
			}
			final int ch = buf[middle++];
			if (ch == '"') {
				final int len = middle - start - 1;
				if (len > 0) {
					dest.write(buf, start, len);
				}
				start = middle;
				return;
			} else if (ch == '\\') {
				final int len = middle - start - 1;
				if (len > 0) {
					dest.write(buf, start, len);
				}
				start = middle;
				dest.write(readEscapedChar());
				middle = start;
			}
		}
	}

	private JsonParseException createParseException(String msg) {
		// We can't tell if EOF was hit by comparing start<=end
		// because the illegal char could have been the last in the buffer
		// or in the stream. To deal with this, the "eof" var was introduced
		if (!eof && start > 0) {
			start--; // backup one char
		}
		final String chs = "char=" + (start >= end ? "(EOF)" : "" + buf[start]);
		final String pos = "position=" + (gpos + start);
		final String tot = chs + ',' + pos + getContext();
		if (msg == null) {
			if (start >= end) {
				msg = "Unexpected EOF";
			} else {
				msg = "JSON Parse Error";
			}
		}
		return new JsonParseException(msg + ": " + tot);
	}

	private String errEscape(final int a, int b) {
		b = Math.min(b, end);
		if (a >= b) {
			return "";
		}
		return new String(buf, a, b - a).replaceAll("\\s+", " ");
	}

	private void fill() throws IOException {
		if (in != null) {
			gpos += end;
			start = 0;
			final int num = in.read(buf, 0, buf.length);
			end = num >= 0 ? num : 0;
		}
		if (start >= end) {
			eof = true;
		}
	}

	private void fillExpectingMore() throws IOException {
		fill();
		if (start >= end) {
			throw createParseException(null);
		}
	}

	/**
	 * Reads a boolean of the input stream
	 *
	 * @return next boolean in the input stream
	 *
	 * @throws IOException
	 *             if the next token is not a boolean
	 */
	public boolean getBoolean() throws IOException {
		goTo(JsonToken.BOOLEAN);
		return lastBoolean;
	}

	private String getContext() {
		String context = "";
		if (start >= 0) {
			context += " BEFORE='" + errEscape(Math.max(start - 60, 0), start + 1) + "'";
		}
		if (start < end) {
			context += " AFTER='" + errEscape(start + 1, start + 40) + "'";
		}
		return context;
	}

	/**
	 * Reads a number from the input stream
	 *
	 * @return next number in the input stream
	 * @throws IOException
	 *             if the next token is not a number
	 */
	public double getDouble() throws IOException {
		return Double.parseDouble(getNumberChars().toString());
	}

	/**
	 * Returns the current nesting level, the number of parent objects or arrays.
	 *
	 * @return the current nesting level
	 */
	public int getLevel() {
		return level;
	}

	/**
	 * Reads a number from the input stream and parses it as a long, only if the value will in fact
	 * fit into a signed 64 bit integer.
	 *
	 * @return next number from the input stream
	 * @throws IOException
	 *             if the next token is not a number
	 */
	public long getLong() throws IOException {
		goTo(JsonToken.LONG);
		return lastLong;
	}

	/**
	 * Reads a null value from the input stream
	 *
	 * @throws IOException
	 *             if the next token is not "null"
	 */
	public void getNull() throws IOException {
		goTo(JsonToken.NULL);
	}

	/**
	 * <p>
	 * Returns the characters of a JSON numeric value.
	 * </p>
	 * <p>
	 * The underlying buffer of the returned <code>CharArr</code> should *not* be modified as it may
	 * be shared with the input buffer.
	 * </p>
	 * <p>
	 * The returned <code>CharSequence</code> will only be valid up until the next JSONParser method
	 * is called. Any required data should be read before that point.
	 * </p>
	 *
	 * @return the characters of the next JSON numeric value
	 * @throws IOException
	 *             if the next token is not a number
	 */
	public CharSequence getNumberChars() throws IOException {
		JsonToken ev = null;
		if (valueState == null) {
			ev = nextEvent();
		}

		if (valueState == JsonToken.LONG || valueState == JsonToken.NUMBER) {
			valueState = null;
			return out;
		} else if (valueState == JsonToken.BIGNUMBER) {
			copyBigNumber(out);
			valueState = null;
			return out;
		} else {
			throw createParseException("Unexpected " + ev);
		}
	}

	public long getPosition() {
		return gpos + start;
	}

	/**
	 * Reads a String value from the input stream, decoding any escaped characters.
	 *
	 * @return the next String value from the input stream
	 * @throws IOException
	 *             if the next value is not a String
	 */
	public String getString() throws IOException {
		return getStringChars().toString();
	}

	/**
	 * <p>
	 * Returns the characters of the JSON string value that would be returned by calling
	 * {@link #getString}.
	 * </p>
	 * <p>
	 * The underlying buffer of the returned <code>CharSequence</code> should *not* be modified as
	 * it may be shared with the input buffer.
	 * </p>
	 * <p>
	 * The returned <code>CharSequence</code> will only be valid up until the next JSONParser method
	 * is called. Any required data should be read before that point.
	 * </p>
	 *
	 * @return the characters of the JSON string value that would be returned by calling
	 *         {@link #getString}
	 * @throws IOException
	 *             if the next value is not a String
	 */
	public CharSequence getStringChars() throws IOException {
		goTo(JsonToken.STRING);
		return readStringChars();
	}

	private void goTo(final JsonToken what) throws IOException {
		if (valueState == what) {
			valueState = null;
			return;
		}
		if (valueState == null) {
			nextEvent();
			if (valueState != what) {
				throw createParseException("type mismatch");
			}
			valueState = null;
		} else {
			throw createParseException("type mismatch");
		}
	}

	/**
	 * Returns true if the given character is considered to be whitespace. One difference between
	 * Java's Character.isWhitespace() is that this method considers a hard space (non-breaking
	 * space, or nbsp) to be whitespace.
	 *
	 * @param ch
	 *            character to be tested
	 * @return true if character is considered to be whitespace
	 */
	private final boolean isWhitespace(final int ch) {
		return Character.isWhitespace(ch) || ch == 0x00a0;
	}

	public JsonToken lastEvent() {
		return event;
	}

	private boolean matchBareWord(final char[] arr) throws IOException {
		for (int i = 1; i < arr.length; i++) {
			final int ch = readChar();
			if (ch != arr[i]) {
				throw createParseException("Expected " + new String(arr));
			}
		}

		return true;
	}

	private JsonToken next(int ch) throws IOException {
		// return the next event when parser is in a neutral state (no
		// map separators or array element separators to read
		for (;;) {
			switch (ch) {
			case ' ': // this is not the exclusive list of whitespace chars... the rest are handled
				// in default:
			case '\t':
			case '\r':
			case '\n':
				ch = readCharUntilWs(); // calling getCharNWS here seems faster than letting the
				// switch
				// handle it
				break;
			case '"':
				valueState = JsonToken.STRING;
				return JsonToken.STRING;
			case '{':
				push();
				state = ParserState.DID_OBJSTART;
				return JsonToken.OBJECT_START;
			case '[':
				push();
				state = ParserState.DID_ARRSTART;
				return JsonToken.ARRAY_START;
			case '0':
				out.reset();
				// special case '0'? If next char isn't '.' val=0
				ch = readChar();
				if (ch == '.') {
					start--;
					ch = '0';
					readNumber('0', false);
					return valueState;
				} else if (ch > '9' || ch < '0') {
					out.unsafeWrite('0');
					if (ch != -1) {
						start--;
					}
					lastLong = 0;
					valueState = JsonToken.LONG;
					return JsonToken.LONG;
				} else {
					throw createParseException("Leading zeros not allowed");
				}
			case '1':
			case '2':
			case '3':
			case '4':
			case '5':
			case '6':
			case '7':
			case '8':
			case '9':
				out.reset();
				lastLong = readNumber(ch, false);
				return valueState;
			case '-':
				out.reset();
				out.unsafeWrite('-');
				ch = readChar();
				if (ch < '0' || ch > '9') {
					throw createParseException("expected digit after '-'");
				}
				lastLong = readNumber(ch, true);
				return valueState;
			case 't':
				if (matchBareWord(TRUE_CHARS)) {
					lastBoolean = true;
					valueState = JsonToken.BOOLEAN;
					return valueState;
				}
				valueState = JsonToken.STRING;
				return JsonToken.STRING;
			case 'f':
				if (matchBareWord(FALSE_CHARS)) {
					lastBoolean = false;
					valueState = JsonToken.BOOLEAN;
					return valueState;
				}
				valueState = JsonToken.STRING;
				return JsonToken.STRING;
			case 'n':
				if (matchBareWord(NULL_CHARS)) {
					valueState = JsonToken.NULL;
					return valueState;
				}
				valueState = JsonToken.STRING;
				return JsonToken.STRING;
			case ']':
				// This only happens with a trailing comma (or an error)
				throw createParseException("Unexpected array closer ]");
			case '}':
				// This only happens with a trailing comma (or an error)
				throw createParseException("Unexpected object closer }");
			case ',':
				// This only happens with input like [1,]
				throw createParseException("Unexpected comma");
			case -1:
				if (getLevel() > 0) {
					throw createParseException("Premature EOF");
				}
				return JsonToken.EOF;
			default:
				// Handle unusual unicode whitespace like no-break space (0xA0)
				if (isWhitespace(ch)) {
					ch = readChar(); // getCharNWS() would also work
					break;
				}
				throw createParseException("Expected quoted string");
			}
		}
	}

	/**
	 * Returns the next event encountered in the input stream, one of
	 * <ul>
	 * <li>{@link JsonToken#STRING}</li>
	 * <li>{@link JsonToken#LONG}</li>
	 * <li>{@link JsonToken#NUMBER}</li>
	 * <li>{@link JsonToken#BIGNUMBER}</li>
	 * <li>{@link JsonToken#BOOLEAN}</li>
	 * <li>{@link JsonToken#NULL}</li>
	 * <li>{@link JsonToken#OBJECT_START}</li>
	 * <li>{@link JsonToken#OBJECT_END}</li>
	 * <li>{@link JsonToken#OBJECT_END}</li>
	 * <li>{@link JsonToken#ARRAY_START}</li>
	 * <li>{@link JsonToken#ARRAY_END}</li>
	 * <li>{@link JsonToken#EOF}</li>
	 * </ul>
	 *
	 * @return the next event encountered in the input stream
	 * @throws IOException
	 *             if there was an error reading from the input stream
	 */
	public JsonToken nextEvent() throws IOException {
		if (valueState != null) {
			if (valueState == JsonToken.STRING) {
				copyStringChars(NULL_OUTPUT, start);
			} else if (valueState == JsonToken.BIGNUMBER) {
				copyBigNumber(NULL_OUTPUT);
			}
			valueState = null;
		}

		int ch;
		for (;;) {
			if (state == null) {
				return event = next(readChar());
			}
			switch (state) {
			case DID_OBJSTART:
				ch = readCharUntilExpected('"');
				if (ch == '}') {
					pop();
					return event = JsonToken.OBJECT_END;
				}
				if (ch != '"') {
					throw createParseException("Expected quoted string");
				}
				state = ParserState.DID_MEMNAME;
				valueState = JsonToken.STRING;
				return event = JsonToken.STRING;
			case DID_MEMNAME:
				ch = readCharUntilExpected(':');
				if (ch != ':') {
					throw createParseException("Expected key,value separator ':'");
				}
				state = ParserState.DID_MEMVAL; // set state first because it might be pushed...
				return event = next(readChar());
			case DID_MEMVAL:
				ch = readCharUntilExpected(',');
				if (ch == '}') {
					pop();
					return event = JsonToken.OBJECT_END;
				} else if (ch != ',') {
					throw createParseException("Expected ',' or '}'");
				}
				ch = readCharUntilExpected('"');
				if (ch != '"') {
					throw createParseException("Expected quoted string");
				}
				state = ParserState.DID_MEMNAME;
				valueState = JsonToken.STRING;
				return event = JsonToken.STRING;
			case DID_ARRSTART:
				ch = readCharUntilWs();
				if (ch == ']') {
					pop();
					return event = JsonToken.ARRAY_END;
				}
				state = ParserState.DID_ARRELEM; // set state first, might be pushed...
				return event = next(ch);
			case DID_ARRELEM:
				ch = readCharUntilExpected(',');
				if (ch == ',') {
					// state = DID_ARRELEM; // redundant
					return event = next(readChar());
				} else if (ch == ']') {
					pop();
					return event = JsonToken.ARRAY_END;
				} else {
					throw createParseException("Expected ',' or ']'");
				}
			}
		}
	}

	private final void pop() throws JsonParseException {
		// pop parser state (use at end of container)
		if (--level < 0) {
			throw createParseException("Unbalanced container");
		}
		state = stack[level];
	}

	private final void push() {
		// push current parser state (use at start of new container)
		if (level >= stack.length) {
			// doubling here is probably overkill, but anything that needs to double more than
			// once (32 levels deep) is very atypical anyway.
			final ParserState[] newstack = new ParserState[stack.length << 1];
			System.arraycopy(stack, 0, newstack, 0, stack.length);
			stack = newstack;
		}
		stack[level++] = state;
	}

	private int readChar() throws IOException {
		if (start >= end) {
			fill();
			if (start >= end) {
				return -1;
			}
		}
		return buf[start++];
	}

	private int readCharUntilExpected(final int expected) throws IOException {
		for (;;) {
			final int ch = readChar();
			if (ch == expected) {
				return expected;
			}
			if (ch == ' ') {
				continue;
			}
			return readCharWhileNotWs(ch);
		}
	}

	private int readCharUntilWs() throws IOException {
		for (;;) {
			final int ch = readChar();
			// getCharNWS is normally called in the context of expecting certain JSON special
			// characters such as ":}"],"; all of these characters are below 64 (including comment
			// chars '/' and '#', so we can make this the fast path even w/o checking the range
			// first. We'll only get some false-positives while using bare strings (chars "IJMc")
			if ((WS_MASK >> ch & 0x01) == 0) {
				return ch;
			} else if (ch <= ' ') { // this will only be true if one of the whitespace bits was set
				continue;
			} else if (!isWhitespace(ch)) { // we'll only reach here with certain bare strings,
				// errors, or strange whitespace like 0xa0
				return ch;
			}
		}
	}

	private int readCharWhileNotWs(int ch) throws IOException {
		for (;;) {
			// getCharNWS is normally called in the context of expecting certain JSON special
			// characters such as ":}"],"
			// all of these characters are below 64 (including comment chars '/' and '#', so we can
			// make this the fast path even w/o checking the range first. We'll only get some
			// false-positives while using bare strings (chars "IJMc")
			if ((WS_MASK >> ch & 0x01) == 0) {
				return ch;
			} else if (ch <= ' ') {
				// this will only be true if one of the whitespace bits was set
				// whitespace... get new char at bottom of loop
			} else if (!isWhitespace(ch)) { // we'll only reach here with certain bare strings,
				// errors, or strange whitespace like 0xa0
				return ch;
			}
			ch = readChar();
		}
	}

	private char readEscapedChar() throws IOException {
		// backslash has already been read when this is called
		final int ch = readChar();
		switch (ch) {
		case '"':
			return '"';
		case '\'':
			return '\'';
		case '\\':
			return '\\';
		case '/':
			return '/';
		case 'n':
			return '\n';
		case 'r':
			return '\r';
		case 't':
			return '\t';
		case 'f':
			return '\f';
		case 'b':
			return '\b';
		case 'u':
			return (char) (toHexDigit(readChar()) << 12 | toHexDigit(readChar()) << 8
					| toHexDigit(readChar()) << 4 | toHexDigit(readChar()));
		}
		throw createParseException("Invalid character escape");
	}

	/**
	 * Returns the long read... only significant if valstate==LONG after this call. firstChar should
	 * be the first numeric digit read.
	 */
	private long readNumber(final int firstChar, final boolean isNeg) throws IOException {
		// unsafe OK since we know output is big enough
		out.unsafeWrite(firstChar);

		// We build up the number in the negative plane since it's larger (by one) than
		// the positive plane.
		long v = '0' - firstChar;

		// can't overflow a long in 18 decimal digits (i.e. 17 additional after the first).
		// we also need 22 additional to handle double so we'll handle in 2 separate loops.
		int i;

		for (i = 0; i < 17; i++) {
			final int ch = readChar();
			switch (ch) {
			case '0':
			case '1':
			case '2':
			case '3':
			case '4':
			case '5':
			case '6':
			case '7':
			case '8':
			case '9':
				v = v * 10 - (ch - '0');
				out.unsafeWrite(ch);
				continue;
			case '.':
				out.unsafeWrite('.');
				valueState = copyFrac(out, 22 - i);
				return 0;
			case 'e':
			case 'E':
				out.unsafeWrite(ch);
				numberState = 0;
				valueState = copyExp(out, 22 - i);
				return 0;
			default:
				// return the number, relying on nextEvent() to return an error
				// for invalid chars following the number.
				if (ch != -1) {
					--start; // push back last char if not EOF
				}

				valueState = JsonToken.LONG;
				return isNeg ? v : -v;
			}
		}

		// after this, we could overflow a long and need to do extra checking
		boolean overflow = false;
		final long maxval = isNeg ? Long.MIN_VALUE : -Long.MAX_VALUE;

		for (; i < 22; i++) {
			final int ch = readChar();
			switch (ch) {
			case '0':
			case '1':
			case '2':
			case '3':
			case '4':
			case '5':
			case '6':
			case '7':
			case '8':
			case '9':
				if (v < 0x8000000000000000L / 10) {
					overflow = true; // can't multiply by 10 w/o overflowing
				}
				v *= 10;
				final int digit = ch - '0';
				if (v < maxval + digit) {
					overflow = true; // can't add digit w/o overflowing
				}
				v -= digit;
				out.unsafeWrite(ch);
				continue;
			case '.':
				out.unsafeWrite('.');
				valueState = copyFrac(out, 22 - i);
				return 0;
			case 'e':
			case 'E':
				out.unsafeWrite(ch);
				numberState = 0;
				valueState = copyExp(out, 22 - i);
				return 0;
			default:
				// return the number, relying on nextEvent() to return an error
				// for invalid chars following the number.
				if (ch != -1) {
					--start; // push back last char if not EOF
				}

				valueState = overflow ? JsonToken.BIGNUMBER : JsonToken.LONG;
				return isNeg ? v : -v;
			}
		}

		numberState = 0;
		valueState = JsonToken.BIGNUMBER;
		return 0;
	}

	private CharSequence readStringChars() throws IOException {
		int i;
		for (i = start; i < end; i++) {
			final char c = buf[i];
			if (c == '"') {
				tmp.set(buf, start, i); // directly use input buffer
				start = i + 1; // advance past last '"'
				return tmp;
			} else if (c == '\\') {
				break;
			}
		}
		out.reset();
		copyStringChars(out, i);
		return out;
	}

	private int toHexDigit(final int hexDigit) throws JsonParseException {
		if (hexDigit >= '0' && hexDigit <= '9') {
			return hexDigit - '0';
		} else if (hexDigit >= 'A' && hexDigit <= 'F') {
			return hexDigit + 10 - 'A';
		} else if (hexDigit >= 'a' && hexDigit <= 'f') {
			return hexDigit + 10 - 'a';
		}
		throw createParseException("invalid hex digit");
	}

	@Override
	public String toString() {
		return "start=" + start + ",end=" + end + ",state=" + state + "valstate=" + valueState;
	}

	public boolean wasKey() {
		return state == ParserState.DID_MEMNAME;
	}
}

class NullCharArr extends SimpleCharArr {
	public NullCharArr() {
		super(new char[1], 0, 0);
	}

	@Override
	public char charAt(final int index) {
		return 0;
	}

	@Override
	public void reserve(final int num) {
	}

	@Override
	public void unsafeWrite(final char b) {
	}

	@Override
	public void unsafeWrite(final int b) {
	}

	@Override
	public void write(final char b[], final int off, final int len) {
	}
}

class SimpleCharArr implements CharSequence {
	protected char[] buf;
	protected int start;
	protected int end;

	public SimpleCharArr() {
		this(32);
	}

	public SimpleCharArr(final char[] arr, final int start, final int end) {
		set(arr, start, end);
	}

	public SimpleCharArr(final int size) {
		buf = new char[size];
	}

	@Override
	public char charAt(final int index) {
		return buf[start + index];
	}

	public void close() {
	}

	@Override
	public int length() {
		return end - start;
	}

	public void reserve(final int num) {
		if (end + num > buf.length) {
			final int newLen = end + num;
			final char newbuf[] = new char[Math.max(buf.length << 1, newLen)];
			System.arraycopy(buf, start, newbuf, 0, length());
			buf = newbuf;
		}
	}

	public final void reset() {
		start = end = 0;
	}

	public void set(final char[] arr, final int start, final int end) {
		this.buf = arr;
		this.start = start;
		this.end = end;
	}

	@Override
	public SimpleCharArr subSequence(final int start, final int end) {
		return new SimpleCharArr(buf, this.start + start, this.start + end);
	}

	@Override
	public String toString() {
		return new String(buf, start, length());
	}

	public void unsafeWrite(final char b) {
		buf[end++] = b;
	}

	public void unsafeWrite(final int b) {
		unsafeWrite((char) b);
	}

	public void write(final char b[], final int off, final int len) {
		reserve(len);
		System.arraycopy(b, off, buf, end, len);
		end += len;
	}

	public final void write(final int b) {
		reserve(1);
		unsafeWrite((char) b);
	}

	public final void write(final SimpleCharArr arr) {
		write(arr.buf, start, end - start);
	}
}
