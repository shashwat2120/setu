package com.setu.shared.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Fetches Identity's JWKS over plain HTTP(S) with short timeouts and a size cap. A blocking {@code HttpURLConnection} on purpose: it needs
 * no selector or client thread pool, and the caller ({@link JwksKeySource}) already rate limits and caches, so there is nothing to gain from
 * an async client. Any non-200 answer, timeout or oversized body is an exception, which the key source turns into "keep the keys I have".
 */
public class HttpJwksFetcher implements JwksKeySource.Fetcher {

    private static final int MAX_BYTES = 64 * 1024;

    private final URI uri;
    private final int timeoutMillis;

    public HttpJwksFetcher(URI uri, Duration timeout) {
        this.uri = uri;
        this.timeoutMillis = (int) timeout.toMillis();
    }

    @Override
    public String fetch() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("Accept", "application/jwk-set+json, application/json");
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("JWKS endpoint answered " + status);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_BYTES) {
                        throw new IOException("JWKS response larger than " + MAX_BYTES + " bytes");
                    }
                }
                return out.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
