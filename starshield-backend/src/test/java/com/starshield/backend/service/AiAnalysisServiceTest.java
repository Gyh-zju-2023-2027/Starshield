package com.starshield.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.model.AiModerationResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAnalysisServiceTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicReference<String> scoreResponse = new AtomicReference<>("{\"score\":0.5}");
    private static final AtomicReference<String> llmResponse = new AtomicReference<>(
            "{\"choices\":[{\"message\":{\"content\":\"{\\\"decision\\\":\\\"REVIEW\\\",\\\"label\\\":\\\"spam\\\",\\\"confidence\\\":0.66,\\\"reason\\\":\\\"suspected spam\\\"}\"}}]}"
    );

    @BeforeAll
    static void setupServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/score", exchange -> {
            byte[] body = scoreResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = llmResponse.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterAll
    static void teardownServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnBlockWhenLightweightScoreHigh() {
        scoreResponse.set("{\"score\":0.92}");
        AiAnalysisService service = newService(() -> "");

        AiModerationResult result = service.analyze("你这个人真恶心");

        assertEquals("BLOCK", result.getDecision());
        assertEquals("lightweight", result.getModelTier());
        assertFalse(result.isDegraded());
        assertTrue(result.getRiskScore() >= 90);
    }

    @Test
    void shouldReturnPassWhenLightweightScoreLow() {
        scoreResponse.set("{\"score\":0.12}");
        AiAnalysisService service = newService(() -> "");

        AiModerationResult result = service.analyze("今天游戏体验不错");

        assertEquals("PASS", result.getDecision());
        assertEquals("lightweight", result.getModelTier());
        assertFalse(result.isDegraded());
        assertTrue(result.getRiskScore() <= 20);
    }

    @Test
    void shouldReturnLlmDecisionWhenLightweightScoreMiddle() {
        scoreResponse.set("{\"score\":0.55}");
        llmResponse.set("{\"choices\":[{\"message\":{\"content\":\"前缀文本 {\\\"decision\\\":\\\"block\\\",\\\"label\\\":\\\"abuse\\\",\\\"confidence\\\":0.88,\\\"reason\\\":\\\"abusive language\\\"} 后缀文本\"}}]}");
        AiAnalysisService service = newService(() -> "test-key");

        AiModerationResult result = service.analyze("边界内容");

        assertEquals("BLOCK", result.getDecision());
        assertEquals("abuse", result.getLabels());
        assertEquals("llm", result.getModelTier());
        assertFalse(result.isDegraded());
        assertTrue(result.getRiskScore() >= 85);
    }

    @Test
    void shouldDegradeWhenLlmKeyMissing() {
        scoreResponse.set("{\"score\":0.55}");
        AiAnalysisService service = newService(() -> "");

        AiModerationResult result = service.analyze("需要进一步判断的内容");

        assertEquals("REVIEW", result.getDecision());
        assertEquals("degraded", result.getModelTier());
        assertTrue(result.isDegraded());
    }

    private AiAnalysisService newService(java.util.function.Supplier<String> apiKeySupplier) {
        ControlPanelService controlPanelService = mock(ControlPanelService.class);
        when(controlPanelService.getPrompt()).thenReturn("你是审核助手，只返回JSON。");

        AiAnalysisService service = new AiAnalysisService(
                RestClient.builder(),
                new ObjectMapper(),
                controlPanelService,
                apiKeySupplier
        );

        ReflectionTestUtils.setField(service, "provider", "deepseek");
        ReflectionTestUtils.setField(service, "promptVersion", "v1");
        ReflectionTestUtils.setField(service, "blockThreshold", 0.8d);
        ReflectionTestUtils.setField(service, "passThreshold", 0.3d);
        ReflectionTestUtils.setField(service, "lightweightUrl", "http://localhost:" + port + "/score");
        ReflectionTestUtils.setField(service, "deepseekUrl", "http://localhost:" + port + "/v1/chat/completions");
        ReflectionTestUtils.setField(service, "dotenvFallbackEnabled", false);
        return service;
    }
}
