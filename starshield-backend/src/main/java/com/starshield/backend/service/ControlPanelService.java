package com.starshield.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 控制台配置服务（敏感词与 Prompt 热管理）。
 */
@Service
public class ControlPanelService {

    private static final String SENSITIVE_WORD_KEY = "starshield:rules:sensitive_words";
    private static final String PROMPT_KEY = "starshield:ai:prompt:current";
    private static final String PROMPT_VERSIONED_KEY_PREFIX = "starshield:ai:prompt:";
    private static final String PROMPT_COMPAT_KEY_PREFIX = "prompt:";

    private static final String DEFAULT_PROMPT_V2 = """
            你是一个中文游戏平台内容安全审核专家。
            
            ## 你的任务
            判断玩家发言是否违规，只返回JSON，不要有任何其他文字。
            
            ## 判断标准
            - BLOCK（明确违规）：辱骂、人身攻击、威胁、诅咒、谐音变体脏话、诈骗引流
            - REVIEW（存疑）：语气激烈但不明确、可能是游戏术语也可能是侮辱
            - PASS（正常）：正常游戏交流、战术讨论、日常闲聊
            
            ## 重要背景知识
            游戏玩家常用谐音/变体规避过滤，以下是常见例子，你需要识别这类变体：
            - "全价四万"="全家死完"（诅咒）
            - "你马四了"="你妈死了"（辱骂）
            - "沙雕/傻叉/傻X"=侮辱性词汇
            - "菜狗/猪队友/废物"=技术嘲讽，轻度
            - "去四/去si"="去死"（诅咒）
            - 数字谐音：4=死，0=o=哦
            
            ## 返回格式
            {"decision":"PASS|REVIEW|BLOCK","label":"normal|abuse|threat|fraud|spam","confidence":0.0到1.0,"reason":"一句话解释，说明判断依据"}
            """;

    @Value("${starshield.ai.prompt-version:v1}")
    private String promptVersion;

    private final StringRedisTemplate stringRedisTemplate;
    private final RuleEngineService ruleEngineService;

    public ControlPanelService(StringRedisTemplate stringRedisTemplate,
                               RuleEngineService ruleEngineService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.ruleEngineService = ruleEngineService;
    }

    /**
     * 获取敏感词列表。
     *
     * @author AI (under P1/P3 supervision)
     */
    public List<String> getSensitiveWords() {
        Set<String> values = stringRedisTemplate.opsForSet().members(SENSITIVE_WORD_KEY);
        if (values == null || values.isEmpty()) {
            return List.of("傻逼", "代充", "加V", "点击链接", "色情");
        }
        return new ArrayList<>(values);
    }

    /**
     * 全量替换敏感词。
     *
     * @author AI (under P1/P3 supervision)
     */
    public void replaceSensitiveWords(List<String> words) {
        ruleEngineService.replaceSensitiveWords(words);
    }

    /**
     * 获取当前 Prompt。
     *
     * @author AI (under P1/P3 supervision)
     */
    public String getPrompt() {
        String version = normalizeVersion(promptVersion);
        String prompt = getFirstNonBlank(
                stringRedisTemplate.opsForValue().get(versionedPromptKey(version)),
                stringRedisTemplate.opsForValue().get(compatPromptKey(version)),
                stringRedisTemplate.opsForValue().get(PROMPT_KEY)
        );
        if (prompt == null) {
            return DEFAULT_PROMPT_V2;
        }
        return prompt;
    }

    /**
     * 更新当前 Prompt。
     *
     * @author AI (under P1/P3 supervision)
     */
    public void setPrompt(String prompt) {
        String value = prompt == null ? "" : prompt;
        String version = normalizeVersion(promptVersion);
        stringRedisTemplate.opsForValue().set(PROMPT_KEY, value);
        stringRedisTemplate.opsForValue().set(versionedPromptKey(version), value);
        stringRedisTemplate.opsForValue().set(compatPromptKey(version), value);
    }

    private String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "v1";
        }
        return version.trim();
    }

    private String versionedPromptKey(String version) {
        return PROMPT_VERSIONED_KEY_PREFIX + version + ":system";
    }

    private String compatPromptKey(String version) {
        return PROMPT_COMPAT_KEY_PREFIX + version + ":system";
    }

    private String getFirstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
