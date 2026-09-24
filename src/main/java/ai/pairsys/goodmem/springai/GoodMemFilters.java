/*
 * Copyright 2026 PAIR Systems, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.pairsys.goodmem.springai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds GoodMem metadata filter expressions safely.
 *
 * <p>
 * GoodMem filters are expression strings evaluated server-side. Interpolating caller
 * data into one is an injection hole and breaks on ordinary data, so these helpers
 * quote values and refuse what the grammar cannot encode. The rules were established
 * against a live server (v1.0.320), not assumed:
 * <ul>
 * <li>escaping is backslash-based: {@code 'o\'brien'}, and a literal backslash is
 * {@code \\}; SQL-style {@code ''} doubling and double-quoted strings are rejected with
 * HTTP 400; a raw newline in a literal is rejected;</li>
 * <li>{@code val()} returns JSON, so every comparison needs a cast that matches the
 * stored type: {@code CAST(val('$.tag') AS TEXT) = 'x'},
 * {@code CAST(val('$.year') AS NUMERIC) > 2000},
 * {@code CAST(val('$.active') AS BOOLEAN) = true}. A boolean compared as text is
 * accepted and matches nothing.</li>
 * </ul>
 */
public final class GoodMemFilters {

	private static final Pattern SAFE_FIELD = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

	private static final Pattern FORBIDDEN_IN_LITERAL = Pattern.compile("[\\x00-\\x1f\\x7f]");

	private GoodMemFilters() {
	}

	/** {@code field = value} on a text field. */
	public static String textEquals(String field, String value) {
		return compare(field, "=", value);
	}

	/**
	 * A typed comparison. The cast is chosen from the value's Java type: {@link Boolean}
	 * → {@code BOOLEAN}, {@link Number} → {@code NUMERIC}, anything else → {@code TEXT}.
	 * @param operator one of {@code = != > >= < <=}
	 */
	public static String compare(String field, String operator, Object value) {
		switch (operator) {
			case "=", "!=", ">", ">=", "<", "<=" -> {
			}
			default -> throw new IllegalArgumentException("Unsupported filter operator: " + operator);
		}
		return "CAST(val('$." + checkField(field) + "') AS " + castOf(value) + ") " + operator + " " + literal(value);
	}

	/** {@code field IN (...)}. An empty collection matches nothing. */
	public static String isIn(String field, Collection<?> values) {
		if (values.isEmpty()) {
			return "1 = 0";
		}
		String cast = null;
		List<String> literals = new ArrayList<>();
		for (Object value : values) {
			String c = castOf(value);
			if (cast == null) {
				cast = c;
			}
			else if (!cast.equals(c)) {
				throw new IllegalArgumentException("An IN filter needs values of one type; got " + cast + " and " + c);
			}
			literals.add(literal(value));
		}
		return "CAST(val('$." + checkField(field) + "') AS " + cast + ") IN (" + String.join(", ", literals) + ")";
	}

	/** AND-join, ignoring nulls and blanks. */
	public static String allOf(String... expressions) {
		return join(" AND ", expressions);
	}

	/** OR-join, ignoring nulls and blanks. */
	public static String anyOf(String... expressions) {
		return join(" OR ", expressions);
	}

	public static String not(String expression) {
		return "NOT (" + expression + ")";
	}

	private static String join(String op, String... expressions) {
		List<String> present = new ArrayList<>();
		for (String e : expressions) {
			if (e != null && !e.isBlank()) {
				present.add(e);
			}
		}
		if (present.isEmpty()) {
			return "";
		}
		if (present.size() == 1) {
			return present.get(0);
		}
		return "(" + String.join(")" + op + "(", present) + ")";
	}

	private static String castOf(Object value) {
		if (value instanceof Boolean) {
			return "BOOLEAN";
		}
		if (value instanceof Number) {
			return "NUMERIC";
		}
		return "TEXT";
	}

	private static String literal(Object value) {
		if (value instanceof Boolean b) {
			return b ? "true" : "false";
		}
		if (value instanceof Number n) {
			return n.toString();
		}
		return quote(String.valueOf(value));
	}

	private static String quote(String value) {
		if (FORBIDDEN_IN_LITERAL.matcher(value).find()) {
			throw new IllegalArgumentException(
					"Metadata filter values cannot contain control characters; the GoodMem filter grammar rejects them.");
		}
		// Backslashes first, so the backslash added for a quote is not escaped again.
		return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
	}

	private static String checkField(String field) {
		if (field == null || !SAFE_FIELD.matcher(field).matches()) {
			throw new IllegalArgumentException("Unsupported metadata field name '" + field
					+ "'. Use letters, digits and underscores, or pass a filter expression directly.");
		}
		return field;
	}

}
