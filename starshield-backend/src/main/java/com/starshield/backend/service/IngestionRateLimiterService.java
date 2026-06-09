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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接入层限流器（Redis 分布式固定窗口，Redis 不可用时可降级为本机窗口）。
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.INGEST})
public class IngestionRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(IngestionRateLimiterService.class);
    private static final RedisScript<Long> FIXED_WINDOW_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);
    private static final long WARN_INTERVAL_MS = 30_000L;

    private final StringRedisTemplate stringRedisTemplate;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private volatile long lastRedisFailureWarnAt = 0L;

    @Value("${starshield.rate-limit.global-qps:20000}")
    private int globalQps;

    @Value("${starshield.rate-limit.player-qps:30}")
    private int playerQps;

    @Value("${starshield.rate-limit.ip-qps:300}")
    private int ipQps;

    @Value("${starshield.rate-limit.window-ms:1000}")
    private long windowMs;

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
        long windowId = System.currentTimeMillis() / windowMs;
        String key = redisKey(scope, identity, windowId);
        long ttlMs = Math.max(windowMs * 2, windowMs + 1000L);
        Long current = stringRedisTemplate.execute(FIXED_WINDOW_SCRIPT, List.of(key), String.valueOf(ttlMs));
        if (current == null) {
            throw new IllegalStateException("Redis rate-limit script returned null");
        }
        return current <= limit;
    }

    private boolean allowLocal(String key, int limit, long windowMs) {
        long now = System.currentTimeMillis();
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter(now, new AtomicInteger(0)));
        synchronized (counter) {
            if (now - counter.windowStart >= windowMs) {
                counter.windowStart = now;
                counter.count.set(0);
            }
            int current = counter.count.incrementAndGet();
            return current <= limit;
        }
    }

    private String redisKey(String scope, String identity, long windowId) {
        return normalizePrefix(keyPrefix)
                + ":" + scope
                + ":" + sha256(identity)
                + ":" + windowId;
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

    private static class WindowCounter {
        long windowStart;
        AtomicInteger count;

        WindowCounter(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
