package com.setu.shared.ai;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Wraps a provider-backed embedder (Gemini, Ollama) for the one place a person is waiting on it: turning a search question into a vector ({@link #embedQuery}).
 * Two things, both about not paying for the provider more than needed:
 * <ul>
 *   <li><b>A cache in Redis.</b> A question's vector is a pure function of the model and the text, so it is kept under {@code setu:ai:embed:<model>:<sha-256 of the text>}
 *       for a day (the model id is in the key, so a model change never serves a vector from the old space). Only the hash of the question is stored, never the
 *       question, and no college or person is named in the key: the vector holds nothing about either. A repeated question, whoever asks it, skips the provider.</li>
 *   <li><b>A cool-down after a failure.</b> A provider that is slow or over its quota made every search wait out the whole timeout in turn (8 s each, one after another, in the
 *       logs). After a failure the provider is not asked again for a short while and search answers from full-text at once, which is what it does on any failure anyway.
 *       A cached question is still answered during the cool-down.</li>
 * </ul>
 * Never the source of truth: if Redis cannot be reached every question simply goes to the provider (logged once a minute). A failure is never cached. {@link #embed},
 * used to index documents, is not touched: it goes straight to the provider every time, because passages are embedded once and a document must not be indexed from a
 * cool-down's silence.
 */
public class CachingTextEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(CachingTextEmbedder.class);

    public static final String KEY_PREFIX = "setu:ai:embed:";
    private static final Duration LOG_EVERY = Duration.ofMinutes(1);

    private final TextEmbedder delegate;
    private final StringRedisTemplate redis;      // null: no cache, the cool-down still applies
    private final Duration ttl;
    private final Duration cooldown;
    private final MeterRegistry meters;           // null: not counted
    private volatile Instant downUntil = Instant.EPOCH;
    private volatile Instant lastRedisLog = Instant.EPOCH;

    public CachingTextEmbedder(TextEmbedder delegate, StringRedisTemplate redis, Duration ttl, Duration cooldown, MeterRegistry meters) {
        this.delegate = delegate;
        this.redis = redis;
        this.ttl = ttl;
        this.cooldown = cooldown;
        this.meters = meters;
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    @Override
    public double minSimilarity() {
        return delegate.minSimilarity();
    }

    @Override
    public Optional<float[]> embed(String text) {
        return delegate.embed(text);
    }

    @Override
    public Optional<float[]> embedQuery(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String key = cacheKey(text);
        Optional<float[]> cached = read(key);
        if (cached.isPresent()) {
            count("hit");
            return cached;
        }
        if (Instant.now().isBefore(downUntil)) {
            count("skipped");   // the provider failed a moment ago: full-text answers, at once
            return Optional.empty();
        }
        Optional<float[]> vector = delegate.embed(text);
        if (vector.isPresent()) {
            downUntil = Instant.EPOCH;
            write(key, vector.get());
            count("miss");
        } else {
            downUntil = Instant.now().plus(cooldown);
            count("failed");
        }
        return vector;
    }

    /** The key a question is kept under: the model, and a hash of the question with its whitespace tidied (nothing else about the text is changed). */
    String cacheKey(String text) {
        String tidy = text.strip().replaceAll("\\s+", " ");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tidy.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + delegate.modelId() + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is required of every Java platform
        }
    }

    private Optional<float[]> read(String key) {
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String stored = redis.opsForValue().get(key);
            if (stored == null) {
                return Optional.empty();
            }
            byte[] bytes = Base64.getDecoder().decode(stored);
            if (bytes.length != DIMENSIONS * Float.BYTES) {
                return Optional.empty();   // not a vector of ours: treated as absent, and replaced by the next answer
            }
            float[] vector = new float[DIMENSIONS];
            ByteBuffer.wrap(bytes).asFloatBuffer().get(vector);
            return Optional.of(vector);
        } catch (RuntimeException e) {
            redisUnavailable("read", e);
            return Optional.empty();
        }
    }

    private void write(String key, float[] vector) {
        if (redis == null) {
            return;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
            buffer.asFloatBuffer().put(vector);
            redis.opsForValue().set(key, Base64.getEncoder().encodeToString(buffer.array()), ttl);
        } catch (RuntimeException e) {
            redisUnavailable("write", e);
        }
    }

    private void count(String result) {
        if (meters != null) {
            meters.counter("setu.ai.embedding.query", "result", result).increment();
        }
    }

    private void redisUnavailable(String what, Exception e) {
        Instant now = Instant.now();
        if (lastRedisLog.plus(LOG_EVERY).isBefore(now)) {
            lastRedisLog = now;
            log.warn("query embedding cache: Redis {} failed ({}); questions go to the provider until it is back", what, e.toString());
        }
    }
}
