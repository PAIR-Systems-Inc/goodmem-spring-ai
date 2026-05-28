/*
 * Copyright 2026 GoodMem
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

import org.jspecify.annotations.Nullable;

/**
 * Runtime exception raised when a call to the GoodMem API fails. Captures the HTTP status
 * code (when available) along with the response body so that callers can surface a clear,
 * actionable message back to the user or AI model.
 */
public class GoodMemClientException extends RuntimeException {

	private final int statusCode;

	@Nullable private final String responseBody;

	public GoodMemClientException(String message) {
		this(message, 0, null, null);
	}

	public GoodMemClientException(String message, @Nullable Throwable cause) {
		this(message, 0, null, cause);
	}

	public GoodMemClientException(String message, int statusCode, @Nullable String responseBody) {
		this(message, statusCode, responseBody, null);
	}

	public GoodMemClientException(String message, int statusCode, @Nullable String responseBody,
			@Nullable Throwable cause) {
		super(message, cause);
		this.statusCode = statusCode;
		this.responseBody = responseBody;
	}

	/**
	 * The HTTP status code returned by the GoodMem API, or {@code 0} when the failure was
	 * not an HTTP error (for example, a network failure or invalid arguments).
	 */
	public int getStatusCode() {
		return this.statusCode;
	}

	/**
	 * The raw response body returned by the GoodMem API, or {@code null} when not
	 * applicable.
	 */
	@Nullable public String getResponseBody() {
		return this.responseBody;
	}

}
