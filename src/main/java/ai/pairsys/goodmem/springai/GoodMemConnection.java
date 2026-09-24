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

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import ai.pairsys.goodmem.client.Goodmem;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;

import org.springframework.util.Assert;

/**
 * Owns, or borrows, the official GoodMem SDK client that every retriever and tool in
 * this package talks through.
 *
 * <p>
 * Build one from a URL and key, or hand in a {@link Goodmem} you already configured.
 * An injected client keeps its own server, credentials, timeouts and TLS settings, and
 * is never closed here. Nothing in this package keeps a process-wide client.
 *
 * <p>
 * Certificate verification is on. {@link Builder#verifySsl(boolean) verifySsl(false)}
 * disables it for <em>this connection only</em>, for a local server with a self-signed
 * certificate; it is not a default and does not belong in a quickstart.
 */
public final class GoodMemConnection implements AutoCloseable {

	private final Goodmem client;

	private final boolean ownsClient;

	private GoodMemConnection(Goodmem client, boolean ownsClient) {
		this.client = client;
		this.ownsClient = ownsClient;
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Wrap a client the caller configured and continues to own.
	 */
	public static GoodMemConnection of(Goodmem client) {
		Assert.notNull(client, "client cannot be null");
		return new GoodMemConnection(client, false);
	}

	/** The SDK client. */
	public Goodmem client() {
		return this.client;
	}

	/** Whether this connection created the client and will close it. */
	public boolean ownsClient() {
		return this.ownsClient;
	}

	@Override
	public void close() {
		if (this.ownsClient) {
			this.client.close();
		}
	}

	public static final class Builder {

		private @Nullable String baseUrl;

		private @Nullable String apiKey;

		private Duration timeout = Duration.ofSeconds(30);

		private boolean verifySsl = true;

		private Builder() {
		}

		public Builder baseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Whether to verify the server's TLS certificate. Defaults to {@code true}.
		 */
		public Builder verifySsl(boolean verifySsl) {
			this.verifySsl = verifySsl;
			return this;
		}

		public GoodMemConnection build() {
			Assert.hasText(this.baseUrl, "baseUrl cannot be null or empty");
			Assert.hasText(this.apiKey, "apiKey cannot be null or empty");
			Assert.notNull(this.timeout, "timeout cannot be null");
			Goodmem.Builder sdk = Goodmem.builder().baseUrl(this.baseUrl).apiKey(this.apiKey);
			if (this.verifySsl) {
				sdk.timeout(this.timeout);
			}
			else {
				// The SDK refuses timeout() next to a custom client; the timeouts live on it.
				sdk.httpClient(insecureHttpClient(this.timeout));
			}
			return new GoodMemConnection(sdk.build(), true);
		}

		private static OkHttpClient insecureHttpClient(Duration timeout) {
			try {
				X509TrustManager trustAll = new X509TrustManager() {
					@Override
					public void checkClientTrusted(X509Certificate[] chain, String authType) {
					}

					@Override
					public void checkServerTrusted(X509Certificate[] chain, String authType) {
					}

					@Override
					public X509Certificate[] getAcceptedIssuers() {
						return new X509Certificate[0];
					}
				};
				SSLContext context = SSLContext.getInstance("TLS");
				context.init(null, new TrustManager[] { trustAll }, new SecureRandom());
				return new OkHttpClient.Builder().sslSocketFactory(context.getSocketFactory(), trustAll)
					.hostnameVerifier((host, session) -> true)
					.connectTimeout(timeout)
					.readTimeout(timeout)
					.writeTimeout(timeout)
					.build();
			}
			catch (java.security.GeneralSecurityException ex) {
				throw new IllegalStateException("Failed to build an insecure TLS context", ex);
			}
		}

	}

}
