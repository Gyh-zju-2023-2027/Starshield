package com.starshield.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.model.AiModerationResult;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAnalysisServiceIntegrationTest {

    @Test
    void shouldCallRealDeepSeekWhenApiKeyProvided() {
        String apiKey = resolveApiKeyForTest();
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "DEEPSEEK_API_KEY 未配置，跳过真实集成测试");

        ControlPanelService controlPanelService = mock(ControlPanelService.class);
        when(controlPanelService.getPrompt()).thenReturn("""
                你是一个中文游戏平台内容安全审核专家。
                判断玩家发言是否违规，只返回JSON，不要有任何其他文字。
                判断标准：
                - BLOCK：辱骂、人身攻击、威胁、诅咒、谐音变体脏话、诈骗引流
                - REVIEW：存疑但不明确
                - PASS：正常交流
                重要背景知识：
                - "全价四万"="全家死完"（诅咒，按BLOCK处理）
                - "你马四了"="你妈死了"（辱骂，按BLOCK处理）
                返回格式：
                {"decision":"PASS|REVIEW|BLOCK","label":"normal|abuse|threat|fraud|spam","confidence":0.0到1.0,"reason":"一句话解释"}
                """);

        AiAnalysisService service = new AiAnalysisService(
                RestClient.builder(),
                new ObjectMapper(),
                controlPanelService
        );

        ReflectionTestUtils.setField(service, "provider", "deepseek");
        ReflectionTestUtils.setField(service, "promptVersion", "v2");
        ReflectionTestUtils.setField(service, "blockThreshold", 0.8d);
        ReflectionTestUtils.setField(service, "passThreshold", 0.3d);
        // 让轻量模型调用失败并回落到 0.5，从而强制进入 LLM 分支
        ReflectionTestUtils.setField(service, "lightweightUrl", "http://127.0.0.1:59999/score");

        AiModerationResult result = service.analyze("你全价四万了");

        System.out.println("=== DeepSeek返回结果 ===");
        System.out.println("decision:   " + result.getDecision());
        System.out.println("label:      " + result.getLabels());
        System.out.println("riskScore:  " + result.getRiskScore());
        System.out.println("reason:     " + result.getReason());
        System.out.println("modelTier:  " + result.getModelTier());
        System.out.println("degraded:   " + result.isDegraded());
        System.out.println("========================");

        assertNotNull(result);
        assertNotNull(result.getDecision());
        assertTrue(
                "PASS".equals(result.getDecision())
                        || "REVIEW".equals(result.getDecision())
                        || "BLOCK".equals(result.getDecision()),
                "decision 非法: " + result.getDecision()
        );
        assertNotNull(result.getReason());
        assertTrue(result.getRiskScore() >= 0 && result.getRiskScore() <= 100);
        assertEquals("llm", result.getModelTier(), "未走到真实LLM成功路径");
        assertFalse(result.isDegraded(), "出现降级，说明真实LLM调用未成功");
    }

    private String resolveApiKeyForTest() {
        String envKey = System.getenv("DEEPSEEK_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey.trim();
        }
        List<Path> candidates = List.of(
                Paths.get(".env").toAbsolutePath().normalize(),
                Paths.get("..", ".env").toAbsolutePath().normalize(),
                Paths.get("..", "..", ".env").toAbsolutePath().normalize()
        );
        for (Path path : candidates) {
            String dotEnvKey = readKeyFromEnvFile(path, "DEEPSEEK_API_KEY");
            if (dotEnvKey != null && !dotEnvKey.isBlank()) {
                return dotEnvKey.trim();
            }
        }
        return null;
    }

    private String readKeyFromEnvFile(Path path, String keyName) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            for (String rawLine : Files.readAllLines(path)) {
                String line = rawLine == null ? "" : rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith(keyName + "=")) {
                    continue;
                }
                String value = line.substring((keyName + "=").length()).trim();
                if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }
}
