package com.starshield.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 接入层限流器（固定窗口）。
 */
@Service
public class IngestionRateLimiterService {

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    @Value("${starshield.rate-limit.global-qps:20000}")
    private int globalQps;

    @Value("${starshield.rate-limit.player-qps:30}")
    private int playerQps;

    @Value("${starshield.rate-limit.ip-qps:300}")
    private int ipQps;

    /**
     * 校验全局限流。
     *
     * @author AI (under P2 supervision)
     */
    public boolean allowGlobal() {
        return allow("global", globalQps, 1000);
    }

    /**
     * 校验玩家维度限流。
     *
     * @author AI (under P2 supervision)
     */
    public boolean allowPlayer(String playerId) {
        return allow("player:" + safe(playerId), playerQps, 1000);
    }

    /**
     * 校验 IP 维度限流。
     *
     * @author AI (under P2 supervision)
     */
    public boolean allowIp(String ip) {
        return allow("ip:" + safe(ip), ipQps, 1000);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    private boolean allow(String key, int limit, long windowMs) {
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

    private static class WindowCounter {
        long windowStart;
        AtomicInteger count;

        WindowCounter(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
