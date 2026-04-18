package com.starshield.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.model.AiModerationResult;
import com.starshield.backend.model.ModerationDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AI 大模型分析服务。
 */
@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final String DEFAULT_FLASK_SCORE_URL = "http://localhost:5000/score";
    private static final String DEEPSEEK_CHAT_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_SYSTEM_PROMPT = "你是游戏聊天审核助手，请输出risk_score、labels、decision、reason。";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ControlPanelService controlPanelService;

    @Value("${starshield.ai.provider:mock}")
    private String provider;

    @Value("${starshield.ai.prompt-version:v1}")
    private String promptVersion;

    @Value("${starshield.ai.block-threshold:0.8}")
    private double blockThreshold;

    @Value("${starshield.ai.pass-threshold:0.3}")
    private double passThreshold;

    @Value("${starshield.ai.lightweight-url:http://localhost:5000/score}")
    private String lightweightUrl;

    @Value("${starshield.ai.deepseek-url:https://api.deepseek.com/v1/chat/completions}")
    private String deepseekUrl;

    private final Supplier<String> apiKeySupplier;

    public AiAnalysisService(RestClient.Builder restClientBuilder,
                             ObjectMapper objectMapper,
                             ControlPanelService controlPanelService) {
        this(restClientBuilder, objectMapper, controlPanelService, () -> System.getenv("DEEPSEEK_API_KEY"));
    }

    AiAnalysisService(RestClient.Builder restClientBuilder,
                      ObjectMapper objectMapper,
                      ControlPanelService controlPanelService,
                      Supplier<String> apiKeySupplier) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.objectMapper = objectMapper;
        this.controlPanelService = controlPanelService;
        this.apiKeySupplier = apiKeySupplier;
    }

    /**
     * 对文本执行深度分析。
     *
     * @author AI (under P3 supervision)
     */
    public AiModerationResult analyze(String content) {
        String text = content == null ? "" : content;
        double score = fetchLightweightScore(text);

        log.debug("[AI分析] lightweight score={}, blockThreshold={}, passThreshold={}", score, blockThreshold, passThreshold);

        if (score >= blockThreshold) {
            return buildLightweightResult(ModerationDecision.BLOCK, score, "abuse", "轻量模型判定高风险");
        }
        if (score < passThreshold) {
            return buildLightweightResult(ModerationDecision.PASS, score, "normal", "轻量模型判定低风险");
        }

        return callDeepSeekForFinalDecision(text, score);
    }

    private AiModerationResult buildLightweightResult(String decision,
                                                      double score,
                                                      String labels,
                                                      String reason) {
        return baseResult()
                .setDecision(decision)
                .setRiskScore(toRiskScore(score))
                .setLabels(labels)
                .setReason(reason)
                .setModelTier("lightweight")
                .setDegraded(false)
                .setConfidence(score);
    }

    private AiModerationResult callDeepSeekForFinalDecision(String content, double fallbackScore) {
        String apiKey = apiKeySupplier.get();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[AI分析] DEEPSEEK_API_KEY 未配置，触发降级");
            return degradedResult("LLM配置缺失，降级人工复核", fallbackScore);
        }

        String prompt = getPromptSafely();
        Map<String, Object> requestBody = Map.of(
                "model", "deepseek-chat",
                "temperature", 0.1,
                "messages", new Object[]{
                        Map.of("role", "system", "content", prompt),
                        Map.of("role", "user", "content", buildUserPrompt(content))
                }
        );

        try {
            log.debug("[AI分析] 调用 DeepSeek 开始");
            String response = restClient.post()
                    .uri(resolveDeepseekUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            log.debug("[AI分析] DeepSeek 返回成功");
            return parseDeepSeekResponse(response, fallbackScore);
        } catch (Exception e) {
            log.warn("[AI分析] DeepSeek 调用失败，触发降级", e);
            return degradedResult("LLM调用失败，降级人工复核", fallbackScore);
        }
    }

    private String getPromptSafely() {
        try {
            String prompt = controlPanelService.getPrompt();
            if (prompt == null || prompt.isBlank()) {
                return DEFAULT_SYSTEM_PROMPT;
            }
            return prompt;
        } catch (Exception e) {
            log.warn("[AI分析] 获取动态 Prompt 失败，使用默认 Prompt", e);
            return DEFAULT_SYSTEM_PROMPT;
        }
    }

    private AiModerationResult parseDeepSeekResponse(String responseBody, double fallbackScore) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        String llmText = root.path("choices").path(0).path("message").path("content").asText("");
        String jsonText = extractJsonObject(llmText);
        JsonNode llmJson = objectMapper.readTree(jsonText);

        String decision = normalizeDecision(llmJson.path("decision").asText(""));
        String label = llmJson.path("label").asText("unknown");
        double confidence = clamp(llmJson.path("confidence").asDouble(fallbackScore), 0d, 1d);
        String reason = llmJson.path("reason").asText("LLM未提供原因");

        if (!isValidDecision(decision)) {
            log.warn("[AI分析] LLM decision 非法，触发降级。decision={}", decision);
            return degradedResult("LLM结果不合法，降级人工复核", fallbackScore);
        }

        return baseResult()
                .setDecision(decision)
                .setRiskScore(toRiskScore(confidence))
                .setLabels(label)
                .setReason(reason)
                .setModelTier("llm")
                .setDegraded(false)
                .setConfidence(confidence);
    }

    private double fetchLightweightScore(String content) {
        try {
            log.debug("[AI分析] 调用轻量模型 /score");
            String responseBody = restClient.post()
                    .uri(resolveLightweightUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", content))
                    .retrieve()
                    .body(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                log.warn("[AI分析] 轻量模型返回空响应，使用默认分数");
                return 0.5d;
            }
            JsonNode response = objectMapper.readTree(responseBody);
            JsonNode scoreNode = response.path("score");
            if (scoreNode.isNumber()) {
                return clamp(scoreNode.asDouble(), 0d, 1d);
            }
            log.warn("[AI分析] 轻量模型 score 字段缺失/非法，使用默认分数。response={}", responseBody);
            return 0.5d;
        } catch (Exception e) {
            log.warn("[AI分析] 轻量模型调用失败，使用默认分数继续走 LLM", e);
            return 0.5d;
        }
    }

    private AiModerationResult degradedResult(String reason, double fallbackScore) {
        return baseResult()
                .setDecision(ModerationDecision.REVIEW)
                .setRiskScore(toRiskScore(fallbackScore))
                .setLabels("degraded")
                .setReason(reason)
                .setModelTier("degraded")
                .setDegraded(true)
                .setConfidence(fallbackScore);
    }

    private AiModerationResult baseResult() {
        return new AiModerationResult()
                .setProvider(provider)
                .setPromptVersion(promptVersion);
    }

    private String buildUserPrompt(String content) {
        return """
                请判断以下游戏发言是否违规，只返回JSON，不要输出其他文本：
                {"decision":"PASS|REVIEW|BLOCK","label":"...","confidence":0.0,"reason":"..."}
                待判断文本：%s
                """.formatted(content);
    }

    private String resolveLightweightUrl() {
        if (lightweightUrl == null || lightweightUrl.isBlank()) {
            return DEFAULT_FLASK_SCORE_URL;
        }
        return lightweightUrl;
    }

    private String resolveDeepseekUrl() {
        if (deepseekUrl == null || deepseekUrl.isBlank()) {
            return DEEPSEEK_CHAT_URL;
        }
        return deepseekUrl;
    }

    private String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM返回中未找到JSON对象");
        }
        return text.substring(start, end + 1);
    }

    private String normalizeDecision(String decision) {
        return decision == null ? "" : decision.trim().toUpperCase();
    }

    private boolean isValidDecision(String decision) {
        return ModerationDecision.PASS.equals(decision)
                || ModerationDecision.REVIEW.equals(decision)
                || ModerationDecision.BLOCK.equals(decision);
    }

    private int toRiskScore(double score) {
        return (int) Math.round(clamp(score, 0d, 1d) * 100d);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
