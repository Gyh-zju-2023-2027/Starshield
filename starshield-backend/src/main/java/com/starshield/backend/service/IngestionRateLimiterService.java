package com.starshield.backend.service;

import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接入层限流器（Redis 分布式滑动窗口 / 令牌桶，Redis 不可用时可降级为本机令牌桶）。
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.INGEST})
public class IngestionRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(IngestionRateLimiterService.class);
    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            local ttl = tonumber(ARGV[5])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            local current = redis.call('ZCARD', KEYS[1])
            if current >= limit then
              redis.call('PEXPIRE', KEYS[1], ttl)
              return 0
            end

            redis.call('ZADD', KEYS[1], now, member)
            redis.call('PEXPIRE', KEYS[1], ttl)
            return 1
            """, Long.class);
    private static final RedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refill_per_ms = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])

            local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'updated_at')
            local tokens = tonumber(bucket[1])
            local updated_at = tonumber(bucket[2])
            if tokens == nil then
              tokens = capacity
            end
            if updated_at == nil or now < updated_at then
              updated_at = now
            end

            tokens = math.min(capacity, tokens + math.max(0, now - updated_at) * refill_per_ms)
            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated_at', now)
            redis.call('PEXPIRE', KEYS[1], ttl)
            return allowed
            """, Long.class);
    private static final long WARN_INTERVAL_MS = 30_000L;
    private static final String ALGORITHM_TOKEN_BUCKET = "token-bucket";
    private static final String ALGORITHM_SLIDING_WINDOW = "sliding-window";

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<String, LocalBucket> localBuckets = new ConcurrentHashMap<>();
    private final AtomicLong requestSequence = new AtomicLong();
    private final String memberPrefix = UUID.randomUUID().toString();
    private volatile long lastRedisFailureWarnAt = 0L;

    @Value("${starshield.rate-limit.global-qps:20000}")
    private int globalQps;

    @Value("${starshield.rate-limit.player-qps:30}")
    private int playerQps;

    @Value("${starshield.rate-limit.ip-qps:300}")
    private int ipQps;

    @Value("${starshield.rate-limit.window-ms:1000}")
    private long windowMs;

    @Value("${starshield.rate-limit.algorithm:sliding-window}")
    private String algorithm;

    @Value("${starshield.rate-limit.redis-enabled:true}")
    private boolean redisEnabled;

    @Value("${starshield.rate-limit.fallback-to-local:true}")
    private boolean fallbackToLocal;

    @Value("${starshield.rate-limit.key-prefix:starshield:rate-limit}")
    private String keyPrefix;

    public IngestionRateLimiterService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 校验全局限流。
     *
     * @author AI (under P2 supervision)
     */
    public boolean allowGlobal() {
        return allow("global", "all", globalQps);
    }

    /**
     * 校验玩家维度限流。
     *
     * @author AI (under P2 supervision)
     */
    public boolean allowPlayer(String playerId) {
        return allow("player", playerId, playerQps);
    }

    /**
     * 校验 IP 维度限流。
     *
     * @author AI (under P2 supervision)
     */
    public boolean allowIp(String ip) {
        return allow("ip", ip, ipQps);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private boolean allow(String scope, String identity, int limit) {
        if (limit <= 0) {
            return false;
        }
        long normalizedWindowMs = Math.max(1L, windowMs);
        String safeIdentity = safe(identity);
        if (redisEnabled) {
            try {
                return allowWithRedis(scope, safeIdentity, limit, normalizedWindowMs);
            } catch (Exception e) {
                warnRedisFailure(e);
                if (!fallbackToLocal) {
                    return false;
                }
            }
        }
        return allowLocal(localKey(scope, safeIdentity), limit, normalizedWindowMs);
    }

    private boolean allowWithRedis(String scope, String identity, int limit, long windowMs) {
        if (ALGORITHM_TOKEN_BUCKET.equals(normalizedAlgorithm())) {
            return allowWithRedisTokenBucket(scope, identity, limit, windowMs);
        }
        return allowWithRedisSlidingWindow(scope, identity, limit, windowMs);
    }

    private boolean allowWithRedisSlidingWindow(String scope, String identity, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        String key = redisKey(scope, identity);
        long ttlMs = Math.max(windowMs * 2, windowMs + 1000L);
        String member = memberPrefix + "-" + now + "-" + requestSequence.incrementAndGet();
        Long allowed = stringRedisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(limit),
                member,
                String.valueOf(ttlMs)
        );
        if (allowed == null) {
            throw new IllegalStateException("Redis rate-limit script returned null");
        }
        return allowed == 1L;
    }

    private boolean allowWithRedisTokenBucket(String scope, String identity, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        String key = redisKey(scope, identity);
        long ttlMs = Math.max(windowMs * 2, windowMs + 1000L);
        double refillPerMs = (double) limit / (double) windowMs;
        Long allowed = stringRedisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(key),
                String.valueOf(now),
                String.valueOf(limit),
                Double.toString(refillPerMs),
                "1",
                String.valueOf(ttlMs)
        );
        if (allowed == null) {
            throw new IllegalStateException("Redis rate-limit script returned null");
        }
        return allowed == 1L;
    }

    private boolean allowLocal(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        LocalBucket bucket = localBuckets.computeIfAbsent(key, k -> new LocalBucket(limit, now));
        synchronized (bucket) {
            if (bucket.capacity != limit) {
                bucket.capacity = limit;
                bucket.tokens = Math.min(bucket.tokens, limit);
            }
            if (now > bucket.updatedAt) {
                double refillPerMs = (double) limit / (double) windowMs;
                bucket.tokens = Math.min(limit, bucket.tokens + (now - bucket.updatedAt) * refillPerMs);
                bucket.updatedAt = now;
            }
            if (bucket.tokens < 1.0d) {
                return false;
            }
            bucket.tokens -= 1.0d;
            return true;
        }
    }

    private String redisKey(String scope, String identity) {
        return normalizePrefix(keyPrefix)
                + ":" + scope
                + ":" + sha256(identity);
    }

    private String localKey(String scope, String identity) {
        return scope + ":" + sha256(identity);
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "starshield:rate-limit";
        }
        return prefix.trim();
    }

    private String normalizedAlgorithm() {
        if (algorithm == null || algorithm.isBlank()) {
            return ALGORITHM_SLIDING_WINDOW;
        }
        String value = algorithm.trim().toLowerCase();
        if (ALGORITHM_TOKEN_BUCKET.equals(value)) {
            return ALGORITHM_TOKEN_BUCKET;
        }
        return ALGORITHM_SLIDING_WINDOW;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    private void warnRedisFailure(Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastRedisFailureWarnAt < WARN_INTERVAL_MS) {
            return;
        }
        lastRedisFailureWarnAt = now;
        log.warn("[接入限流] Redis 限流不可用，fallbackToLocal={}, reason={}", fallbackToLocal, e.toString());
        log.debug("[接入限流] Redis 限流异常堆栈", e);
    }

    private static class LocalBucket {
        int capacity;
        double tokens;
        long updatedAt;

        LocalBucket(int capacity, long updatedAt) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.updatedAt = updatedAt;
        }
    }
}
