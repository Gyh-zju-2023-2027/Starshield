package com.starshield.backend.service;

import com.starshield.backend.model.FastCheckResult;
import com.starshield.backend.model.ModerationDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 引擎 A：Redis 敏感词快速拦截。
 */
@Service
public class RuleEngineService {

    private static final String SENSITIVE_WORD_KEY = "starshield:rules:sensitive_words";
    private static final long CACHE_SECONDS = 10L;

    private final StringRedisTemplate stringRedisTemplate;

    private volatile long cacheExpireAt = 0L;
    private volatile List<String> cachedWords = List.of();

    public RuleEngineService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 执行高速规则判定。
     *
     * @author AI (under P4 supervision)
     */
    public FastCheckResult fastCheck(String content) {
        String normalized = normalize(content);
        List<String> hitWords = new ArrayList<>();

        for (String word : loadSensitiveWords()) {
            if (!word.isBlank() && normalized.contains(word)) {
                hitWords.add(word);
            }
        }

        if (hitWords.isEmpty()) {
            return new FastCheckResult()
                    .setDecision(ModerationDecision.PASS)
                    .setRiskScore(10)
                    .setLabels("normal")
                    .setHitWords("")
                    .setReason("规则引擎未命中");
        }

        int hitCount = hitWords.size();
        int risk = Math.min(95, 55 + hitCount * 15);
        String decision = risk >= 80 ? ModerationDecision.BLOCK : ModerationDecision.REVIEW;
        return new FastCheckResult()
                .setDecision(decision)
                .setRiskScore(risk)
                .setLabels("keyword_violation")
                .setHitWords(String.join(",", hitWords))
                .setReason("命中敏感词");
    }

    /**
     * 动态更新敏感词（热生效）。
     *
     * @author AI (under P4 supervision)
     */
    public void replaceSensitiveWords(List<String> words) {
        stringRedisTemplate.delete(SENSITIVE_WORD_KEY);
        if (words != null && !words.isEmpty()) {
            stringRedisTemplate.opsForSet().add(SENSITIVE_WORD_KEY, words.toArray(new String[0]));
        }
        cacheExpireAt = 0L;
    }

    private List<String> loadSensitiveWords() {
        long now = System.currentTimeMillis();
        if (now < cacheExpireAt && !cachedWords.isEmpty()) {
            return cachedWords;
        }

        Set<String> words = stringRedisTemplate.opsForSet().members(SENSITIVE_WORD_KEY);
        if (words == null || words.isEmpty()) {
            cachedWords = Arrays.asList("傻逼", "代充", "加V", "点击链接", "色情");
        } else {
            cachedWords = new ArrayList<>(words);
        }
        cacheExpireAt = now + TimeUnit.SECONDS.toMillis(CACHE_SECONDS);
        return cachedWords;
    }

    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        return content.toLowerCase()
                .replaceAll("\\s+", "")
                .replace("*", "")
                .replace("-", "");
    }
}
