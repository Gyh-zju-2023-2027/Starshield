package com.starshield.backend.service;

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
        String prompt = stringRedisTemplate.opsForValue().get(PROMPT_KEY);
        if (prompt == null || prompt.isBlank()) {
            return "你是游戏聊天审核助手，请输出risk_score、labels、decision、reason。";
        }
        return prompt;
    }

    /**
     * 更新当前 Prompt。
     *
     * @author AI (under P1/P3 supervision)
     */
    public void setPrompt(String prompt) {
        stringRedisTemplate.opsForValue().set(PROMPT_KEY, prompt == null ? "" : prompt);
    }
}
