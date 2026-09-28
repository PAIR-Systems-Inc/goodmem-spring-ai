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

import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The one check every GoodMem id in this package passes before it reaches the SDK.
 *
 * <p>
 * The SDK builds paths such as {@code "/v1/memories/" + id} and hands them to OkHttp's
 * {@code addEncodedPathSegments}, which resolves {@code ..} and {@code %2e%2e} before
 * sending. A memory id of {@code ../spaces/<uuid>} therefore turned
 * {@code goodmem_delete_memory} into {@code DELETE /v1/spaces/<uuid>}. Every GoodMem id
 * (space, memory, embedder, reranker) is a UUID, so anything that is not a canonical
 * UUID is refused here, before any request is made, and a UUID is lower-cased.
 */
final class GoodMemIds {

	private static final Pattern UUID = Pattern
		.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

	private GoodMemIds() {
	}

	/**
	 * Return {@code value} lower-cased if it is a canonical UUID; otherwise throw.
	 * {@link java.util.regex.Matcher#matches()} is used, so a trailing newline is not
	 * accepted the way a bare {@code $} would accept it.
	 * @param field the argument's name as the caller sees it, for the message
	 * @throws InvalidIdException if {@code value} is null or not a UUID
	 */
	static String requireUuid(@Nullable String value, String field) {
		if (value == null || !UUID.matcher(value).matches()) {
			throw new InvalidIdException(field);
		}
		return value.toLowerCase(Locale.ROOT);
	}

	/**
	 * An id that is not a UUID. The message names the field and never repeats the value,
	 * which may carry control characters into a log.
	 */
	static final class InvalidIdException extends IllegalArgumentException {

		private static final long serialVersionUID = 1L;

		InvalidIdException(String field) {
			super(field + " must be a UUID (8-4-4-4-12 hexadecimal digits); the value given is not one, "
					+ "so nothing was sent to GoodMem.");
		}

	}

}
