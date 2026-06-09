package com.starshield.backend.service;

import com.starshield.backend.model.FastCheckResult;
import com.starshield.backend.model.ModerationDecision;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RuleEngineServiceTest {

    private StringRedisTemplate mockRedisTemplate;
    private SetOperations<String, String> mockSetOps;
    private RuleEngineService ruleEngineService;

    @BeforeEach
    void setUp() {
        mockRedisTemplate = mock(StringRedisTemplate.class);
        mockSetOps = mock(SetOperations.class);
        when(mockRedisTemplate.opsForSet()).thenReturn(mockSetOps);
        ruleEngineService = new RuleEngineService(mockRedisTemplate);
    }

    @Test
    void shouldReturnPassWhenContentNotContainsSensitiveWords() {
        // 准备测试数据
        Set<String> sensitiveWords = new HashSet<>(Arrays.asList("傻逼", "测试词", "违规"));
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        // 执行测试
        FastCheckResult result = ruleEngineService.fastCheck("这是一条正常的发言内容");

        // 验证结果
        assertEquals(ModerationDecision.PASS, result.getDecision());
        assertEquals(10, result.getRiskScore());
        assertEquals("normal", result.getLabels());
        assertEquals("", result.getHitWords());
        assertEquals("布隆过滤器未发现候选敏感词", result.getReason());
    }

    @Test
    void shouldReturnReviewWhenSingleSensitiveWordHit() {
        // 准备测试数据
        Set<String> sensitiveWords = new HashSet<>(Arrays.asList("傻逼", "测试词"));
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        // 执行测试 - 单个敏感词命中
        FastCheckResult result = ruleEngineService.fastCheck("你这个傻逼真的很过分");

        // 验证结果 - 风险评分应为 55 + 1 * 15 = 70，决策为REVIEW
        assertEquals(ModerationDecision.REVIEW, result.getDecision());
        assertTrue(result.getRiskScore() >= 70 && result.getRiskScore() < 80);
        assertEquals("keyword_violation", result.getLabels());
        assertTrue(result.getHitWords().contains("傻逼"));
        assertEquals("命中敏感词", result.getReason());
    }

    @Test
    void shouldReturnBlockWhenMultipleSensitiveWordsHit() {
        // 准备测试数据
        Set<String> sensitiveWords = new HashSet<>(Arrays.asList("傻逼", "违规"));
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        // 执行测试 - 多个敏感词命中
        FastCheckResult result = ruleEngineService.fastCheck("你这个傻逼违规了");

        // 验证结果 - 风险评分应为 55 + 2 * 15 = 85，大于80，应为BLOCK
        assertEquals(ModerationDecision.BLOCK, result.getDecision());
        assertTrue(result.getRiskScore() >= 80);
        assertEquals("keyword_violation", result.getLabels());
        assertTrue(result.getHitWords().contains("傻逼"));
        assertTrue(result.getHitWords().contains("违规"));
        assertEquals("命中敏感词", result.getReason());
    }

    @Test
    void shouldCalculateRiskScoreCorrectly() {
        // 准备测试数据
        Set<String> sensitiveWords = new HashSet<>(Arrays.asList("词1", "词2", "词3", "词4", "词5"));
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        // 执行测试 - 5个敏感词命中
        FastCheckResult result = ruleEngineService.fastCheck("包含词1词2词3词4词5的内容");

        // 验证风险评分计算：55 + 5 * 15 = 130，但应被限制在95以内
        assertTrue(result.getRiskScore() <= 95);
        assertEquals(ModerationDecision.BLOCK, result.getDecision());
    }

    @Test
    void shouldHandleTextNormalization() {
        // 准备测试数据
        Set<String> sensitiveWords = new HashSet<>(Arrays.asList("傻逼"));
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        // 验证文本标准化处理 - 移除空格和特殊字符
        FastCheckResult result = ruleEngineService.fastCheck("你  是  傻  逼 吗");
        // 标准化后变成 "你是傻逼吗"，应该能匹配到"傻逼"
        assertEquals(ModerationDecision.REVIEW, result.getDecision());
        assertTrue(result.getHitWords().contains("傻逼"));
    }

    @Test
    void shouldNormalizeSensitiveWordsBeforeMatching() {
        Set<String> sensitiveWords = new HashSet<>(Arrays.asList("加V"));
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        FastCheckResult result = ruleEngineService.fastCheck("想低价代练请加v");

        assertEquals(ModerationDecision.REVIEW, result.getDecision());
        assertTrue(result.getHitWords().contains("加V"));
    }

    @Test
    void shouldReturnPassForNullContentBasedOnActualBehavior() {
        // 准备测试数据 - 使用默认敏感词
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(null);

        // 执行测试
        FastCheckResult result = ruleEngineService.fastCheck(null);

        // 验证结果 - 根据实际行为验证
        assertEquals(ModerationDecision.PASS, result.getDecision());
        assertEquals(10, result.getRiskScore());
    }

    @Test
    void shouldReturnPassForEmptyContentBasedOnActualBehavior() {
        // 准备测试数据 - 使用默认敏感词
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(null);

        // 执行测试
        FastCheckResult result = ruleEngineService.fastCheck("");

        // 验证结果 - 根据实际行为验证
        assertEquals(ModerationDecision.PASS, result.getDecision());
        assertEquals(10, result.getRiskScore());
    }

    @Test
    void shouldHandleRedisEmptySensitiveWords() {
        // 模拟Redis中没有敏感词的情况
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(null);

        // 执行测试
        FastCheckResult result = ruleEngineService.fastCheck("这是一条测试内容");

        // 验证使用默认敏感词列表
        assertEquals(ModerationDecision.PASS, result.getDecision());
        assertEquals(10, result.getRiskScore());
    }

    @Test
    void shouldUpdateSensitiveWordsInRedis() {
        // 准备测试数据
        List<String> newWords = Arrays.asList("新词1", "新词2", "新词3");

        // 执行更新
        ruleEngineService.replaceSensitiveWords(newWords);

        // 验证Redis操作 - 这部分应该能成功验证
        verify(mockRedisTemplate).delete("starshield:rules:sensitive_words");
        verify(mockSetOps).add(eq("starshield:rules:sensitive_words"), any(String[].class));
    }

    @Test
    void shouldBypassBloomFilterForNonSensitiveContentBasedOnActualBehavior() {
        // 准备测试数据
        List<String> initialWords = Arrays.asList("测试词");
        ruleEngineService.replaceSensitiveWords(initialWords);
        
        Set<String> sensitiveWords = new HashSet<>(initialWords);
        when(mockSetOps.members("starshield:rules:sensitive_words")).thenReturn(sensitiveWords);

        // 测试不包含敏感词的内容
        FastCheckResult result = ruleEngineService.fastCheck("完全正常的内容");

        // 验证 BloomFilter 安全快速排除
        assertEquals(ModerationDecision.PASS, result.getDecision());
        assertEquals(10, result.getRiskScore());
    }

    @Test
    void shouldHandleMaxRiskScoreLimitBasedOnActualBehavior() {
        // 准备大量敏感词以达到最大风险评分
        List<String> manyWords = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            manyWords.add("词" + i);
        }
        
        ruleEngineService.replaceSensitiveWords(manyWords);

        // 构造包含所有敏感词的内容
        StringBuilder contentBuilder = new StringBuilder();
        for (String word : manyWords) {
            contentBuilder.append(word).append(" ");
        }
        
        // 风险评分 = 55 + 10 * 15 = 205，但应被限制在95以内
        FastCheckResult result = ruleEngineService.fastCheck(contentBuilder.toString());

        assertEquals(ModerationDecision.BLOCK, result.getDecision());
        assertEquals(95, result.getRiskScore());
        for (String word : manyWords) {
            assertTrue(result.getHitWords().contains(word));
        }
    }
    @Test
    void shouldHandleBoundaryRiskScoreValuesBasedOnActualBehavior() {
        // 测试边界情况
        List<String> wordsFor80Score = new ArrayList<>();
        // 55 + n * 15 >= 80 => n >= 1.67，所以用2个词应该超过80
        for (int i = 0; i < 2; i++) {
            wordsFor80Score.add("词" + i);
        }
        
        ruleEngineService.replaceSensitiveWords(wordsFor80Score);

        FastCheckResult result = ruleEngineService.fastCheck("词0 词1");
        
        assertEquals(ModerationDecision.BLOCK, result.getDecision());
        assertTrue(result.getRiskScore() >= 80,
                  "Risk score should be at least 80 when 2 words are hit, but was: " + result.getRiskScore());
        assertTrue(result.getHitWords().contains("词0"));
        assertTrue(result.getHitWords().contains("词1"));
    }

    @Test
    void shouldHandleJustBelowBoundaryRiskScoreBasedOnActualBehavior() {
        // 测试79分的情况
        List<String> wordsFor79Score = new ArrayList<>();
        // 55 + n * 15 < 80 => n < 1.6，所以用1个词应该为70分，是REVIEW
        wordsFor79Score.add("词0");
        
        ruleEngineService.replaceSensitiveWords(wordsFor79Score);

        FastCheckResult result = ruleEngineService.fastCheck("词0");
        assertNotEquals(ModerationDecision.PASS, result.getDecision(), "Decision should not be PASS when sensitive words are hit");
        assertTrue(result.getRiskScore() < 80, "Risk score should be less than 80, but was: " + result.getRiskScore());
        assertEquals(ModerationDecision.REVIEW, result.getDecision());
    }
}
