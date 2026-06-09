package com.starshield.backend.service;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.starshield.backend.model.FastCheckResult;
import com.starshield.backend.model.ModerationDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 引擎 A：Redis 敏感词快速拦截 + 布隆过滤器优化。
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.WORKER, RuntimeMode.API})
public class RuleEngineService {

    private static final String SENSITIVE_WORD_KEY = "starshield:rules:sensitive_words";
    private static final long CACHE_SECONDS = 10L;

    private final StringRedisTemplate stringRedisTemplate;

    private volatile long cacheExpireAt = 0L;
    private volatile List<String> cachedWords = List.of();
    private volatile Set<Integer> cachedWordLengths = Set.of();
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
        List<String> sensitiveWords = loadSensitiveWords();

        if (bloomFilter != null && !mightContainAnySensitiveWord(normalized)) {
            return new FastCheckResult()
                    .setDecision(ModerationDecision.PASS)
                    .setRiskScore(10)
                    .setLabels("normal")
                    .setHitWords("")
                    .setReason("布隆过滤器未发现候选敏感词");
        }

        List<String> hitWords = new ArrayList<>();
        for (String word : sensitiveWords) {
            String normalizedWord = normalize(word);
            if (!normalizedWord.isBlank() && normalized.contains(normalizedWord)) {
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
        List<String> sanitizedWords = sanitizeWords(words);
        stringRedisTemplate.delete(SENSITIVE_WORD_KEY);
        if (!sanitizedWords.isEmpty()) {
            stringRedisTemplate.opsForSet().add(SENSITIVE_WORD_KEY, sanitizedWords.toArray(new String[0]));
            cachedWords = sanitizedWords;
            rebuildBloomFilter(sanitizedWords);
            cacheExpireAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(CACHE_SECONDS);
        } else {
            cachedWords = List.of();
            cachedWordLengths = Set.of();
            bloomFilter = null;
            cacheExpireAt = 0L;
        }
    }

    private void rebuildBloomFilter(List<String> words) {
        if (words == null || words.isEmpty()) {
            cachedWordLengths = Set.of();
            bloomFilter = null;
            return;
        }

        LinkedHashSet<String> normalizedWords = new LinkedHashSet<>();
        Set<Integer> wordLengths = new HashSet<>();
        for (String word : words) {
            String normalizedWord = normalize(word);
            if (!normalizedWord.isBlank()) {
                normalizedWords.add(normalizedWord);
                wordLengths.add(normalizedWord.length());
            }
        }
        if (normalizedWords.isEmpty()) {
            cachedWordLengths = Set.of();
            bloomFilter = null;
            return;
        }

        int expectedInsertions = normalizedWords.size();
        double fpp = 0.01; // 1% 误判率
        
        BloomFilter<String> newBloomFilter = BloomFilter.create(
            Funnels.stringFunnel(StandardCharsets.UTF_8),
            expectedInsertions,
            fpp
        );
        
        for (String word : normalizedWords) {
            newBloomFilter.put(word);
        }
        
        this.cachedWordLengths = Set.copyOf(wordLengths);
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
            cachedWords = sanitizeWords(new ArrayList<>(words));
            rebuildBloomFilter(cachedWords); // 重建布隆过滤器
        }
        cacheExpireAt = now + TimeUnit.SECONDS.toMillis(CACHE_SECONDS);
        return cachedWords;
    }

    private boolean mightContainAnySensitiveWord(String normalizedContent) {
        if (normalizedContent == null || normalizedContent.isBlank() || cachedWordLengths.isEmpty()) {
            return false;
        }
        for (Integer wordLength : cachedWordLengths) {
            if (wordLength == null || wordLength <= 0 || wordLength > normalizedContent.length()) {
                continue;
            }
            int lastStart = normalizedContent.length() - wordLength;
            for (int start = 0; start <= lastStart; start++) {
                if (bloomFilter.mightContain(normalizedContent.substring(start, start + wordLength))) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> sanitizeWords(List<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> sanitized = new LinkedHashSet<>();
        for (String word : words) {
            if (word != null && !word.trim().isEmpty()) {
                sanitized.add(word.trim());
            }
        }
        return new ArrayList<>(sanitized);
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
