package com.starshield.backend.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.starshield.backend.model.FastCheckResult;
import com.starshield.backend.model.ModerationDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 引擎 A：Redis 敏感词快速拦截 + 布隆过滤器优化。
 */
@Service
public class RuleEngineService {

    private static final String SENSITIVE_WORD_KEY = "starshield:rules:sensitive_words";
    private static final String BLOOM_FILTER_KEY = "starshield:rules:bloom_filter";
    private static final long CACHE_SECONDS = 10L;

    private final StringRedisTemplate stringRedisTemplate;

    private volatile long cacheExpireAt = 0L;
    private volatile List<String> cachedWords = List.of();
    private volatile BloomFilter<String> bloomFilter;

    public RuleEngineService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 执行高速规则判定（使用布隆过滤器优化）。
     *
     * @author AI (under P4 supervision)
     */
    public FastCheckResult fastCheck(String content) {
        String normalized = normalize(content);
        
        // 使用布隆过滤器快速判断是否可能包含敏感词
        if (bloomFilter != null && !bloomFilter.mightContain(normalized)) {
            // 布隆过滤器确认不包含敏感词，直接返回PASS
            return new FastCheckResult()
                    .setDecision(ModerationDecision.PASS)
                    .setRiskScore(10)
                    .setLabels("normal")
                    .setHitWords("")
                    .setReason("布隆过滤器快速判断无敏感词");
        }

        // 可能包含敏感词，进行精确匹配
        List<String> hitWords = new ArrayList<>();
        for (String word : loadSensitiveWords()) {
            if (!word.isBlank() && normalized.contains(word)) {
                hitWords.add(word);
            }
        }

        if (hitWords.isEmpty()) {
            return new FastCheckResult()
                    .setDecision(ModerationDecision.PASS)
                    .setRiskScore(20) // 略高于直接通过的情况
                    .setLabels("normal")
                    .setHitWords("")
                    .setReason("规则引擎未命中，布隆过滤器可能误判");
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
            
            // 更新布隆过滤器
            rebuildBloomFilter(words);
        } else {
            bloomFilter = null; // 清空布隆过滤器
        }
        cacheExpireAt = 0L;
    }

    private void rebuildBloomFilter(List<String> words) {
        if (words == null || words.isEmpty()) {
            bloomFilter = null;
            return;
        }
        
        // 创建布隆过滤器，预期元素数量为敏感词数量，误判率为1%
        int expectedInsertions = words.size();
        double fpp = 0.01; // 1% 误判率
        
        BloomFilter<String> newBloomFilter = BloomFilter.create(
            Funnels.stringFunnel(Charset.defaultCharset()),
            expectedInsertions,
            fpp
        );
        
        // 将所有敏感词添加到布隆过滤器
        for (String word : words) {
            if (word != null && !word.trim().isEmpty()) {
                newBloomFilter.put(word.trim());
            }
        }
        
        this.bloomFilter = newBloomFilter;
    }

    private List<String> loadSensitiveWords() {
        long now = System.currentTimeMillis();
        if (now < cacheExpireAt && !cachedWords.isEmpty()) {
            return cachedWords;
        }

        Set<String> words = stringRedisTemplate.opsForSet().members(SENSITIVE_WORD_KEY);
        if (words == null || words.isEmpty()) {
            cachedWords = Arrays.asList("傻逼", "代充", "加V", "点击链接", "色情");
            rebuildBloomFilter(cachedWords); // 重建布隆过滤器
        } else {
            cachedWords = new ArrayList<>(words);
            rebuildBloomFilter(cachedWords); // 重建布隆过滤器
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