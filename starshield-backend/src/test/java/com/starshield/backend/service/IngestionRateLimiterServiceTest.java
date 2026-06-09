package com.starshield.backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngestionRateLimiterServiceTest {

    private StringRedisTemplate redisTemplate;
    private IngestionRateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        rateLimiterService = new IngestionRateLimiterService(redisTemplate);
        ReflectionTestUtils.setField(rateLimiterService, "globalQps", 2);
        ReflectionTestUtils.setField(rateLimiterService, "ipQps", 2);
        ReflectionTestUtils.setField(rateLimiterService, "playerQps", 2);
        ReflectionTestUtils.setField(rateLimiterService, "windowMs", 1000L);
        ReflectionTestUtils.setField(rateLimiterService, "redisEnabled", true);
        ReflectionTestUtils.setField(rateLimiterService, "fallbackToLocal", true);
        ReflectionTestUtils.setField(rateLimiterService, "keyPrefix", "test:rate-limit");
    }

    @Test
    void shouldLimitByRedisCounter() {
        when(redisTemplate.execute(anyRedisScript(), anyStringList(), any()))
                .thenReturn(1L, 2L, 3L);

        assertTrue(rateLimiterService.allowPlayer("player-1"));
        assertTrue(rateLimiterService.allowPlayer("player-1"));
        assertFalse(rateLimiterService.allowPlayer("player-1"));
    }

    @Test
    void shouldFallbackToLocalWindowWhenRedisFails() {
        when(redisTemplate.execute(anyRedisScript(), anyStringList(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        ReflectionTestUtils.setField(rateLimiterService, "playerQps", 1);

        assertTrue(rateLimiterService.allowPlayer("player-1"));
        assertFalse(rateLimiterService.allowPlayer("player-1"));
    }

    @Test
    void shouldDenyWhenRedisFailsAndFallbackDisabled() {
        when(redisTemplate.execute(anyRedisScript(), anyStringList(), any()))
                .thenThrow(new IllegalStateException("redis down"));
        ReflectionTestUtils.setField(rateLimiterService, "fallbackToLocal", false);

        assertFalse(rateLimiterService.allowIp("127.0.0.1"));
    }

    @Test
    void shouldUseLocalLimiterWhenRedisDisabled() {
        ReflectionTestUtils.setField(rateLimiterService, "redisEnabled", false);
        ReflectionTestUtils.setField(rateLimiterService, "globalQps", 1);

        assertTrue(rateLimiterService.allowGlobal());
        assertFalse(rateLimiterService.allowGlobal());
    }

    @SuppressWarnings("unchecked")
    private RedisScript<Long> anyRedisScript() {
        return (RedisScript<Long>) any(RedisScript.class);
    }

    @SuppressWarnings("unchecked")
    private List<String> anyStringList() {
        return (List<String>) (List<?>) anyList();
    }
}
