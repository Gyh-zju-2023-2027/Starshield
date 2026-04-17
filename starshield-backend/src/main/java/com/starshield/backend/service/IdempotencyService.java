package com.starshield.backend.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 幂等键服务（Redis）。
 */
@Service
public class IdempotencyService {

    private static final String KEY_PREFIX = "starshield:idem:";
    private static final long TTL_MINUTES = 5L;
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('exists', KEYS[1]) == 1 then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    private final StringRedisTemplate stringRedisTemplate;

    public IdempotencyService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成幂等键。
     *
     * @author AI (under P2/P5 supervision)
     */
    public String createKey() {
        String key = UUID.randomUUID().toString();
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + key, "1", TTL_MINUTES, TimeUnit.MINUTES);
        return key;
    }

    /**
     * 消费幂等键（一次性）。
     *
     * @author AI (under P2/P5 supervision)
     */
    public boolean consumeKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String redisKey = KEY_PREFIX + key;
        Long deleted = stringRedisTemplate.execute(CONSUME_SCRIPT, Collections.singletonList(redisKey));
        return deleted != null && deleted > 0L;
    }
}
