package com.starshield.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.model.AiModerationResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiAnalysisServiceTest {

    private static final String SCORE_URL = "http://ai.test/score";
    private static final String LLM_URL = "http://deepseek.test/v1/chat/completions";

    @Test
    void shouldReturnBlockWhenLightweightScoreHigh() {
        TestFixture fixture = newFixture(() -> "");
        fixture.server.expect(requestTo(SCORE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"score\":0.92}", MediaType.APPLICATION_JSON));

        AiModerationResult result = fixture.service.analyze("你这个人真恶心");

        assertEquals("BLOCK", result.getDecision());
        assertEquals("lightweight", result.getModelTier());
        assertFalse(result.isDegraded());
        assertTrue(result.getRiskScore() >= 90);
        fixture.server.verify();
    }

    @Test
    void shouldReturnPassWhenLightweightScoreLow() {
        TestFixture fixture = newFixture(() -> "");
        fixture.server.expect(requestTo(SCORE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"score\":0.12}", MediaType.APPLICATION_JSON));

        AiModerationResult result = fixture.service.analyze("今天游戏体验不错");

        assertEquals("PASS", result.getDecision());
        assertEquals("lightweight", result.getModelTier());
        assertFalse(result.isDegraded());
        assertTrue(result.getRiskScore() <= 20);
        fixture.server.verify();
    }

    @Test
    void shouldReturnLlmDecisionWhenLightweightScoreMiddle() {
        TestFixture fixture = newFixture(() -> "test-key");
        fixture.server.expect(requestTo(SCORE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"score\":0.55}", MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(LLM_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"前缀文本 {\\\"decision\\\":\\\"block\\\",\\\"label\\\":\\\"abuse\\\",\\\"confidence\\\":0.88,\\\"reason\\\":\\\"abusive language\\\"} 后缀文本\"}}]}",
                        MediaType.APPLICATION_JSON
                ));

        AiModerationResult result = fixture.service.analyze("边界内容");

        assertEquals("BLOCK", result.getDecision());
        assertEquals("abuse", result.getLabels());
        assertEquals("llm", result.getModelTier());
        assertFalse(result.isDegraded());
        assertTrue(result.getRiskScore() >= 85);
        fixture.server.verify();
    }

    @Test
    void shouldDegradeWhenLlmKeyMissing() {
        TestFixture fixture = newFixture(() -> "");
        fixture.server.expect(requestTo(SCORE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"score\":0.55}", MediaType.APPLICATION_JSON));

        AiModerationResult result = fixture.service.analyze("需要进一步判断的内容");

        assertEquals("REVIEW", result.getDecision());
        assertEquals("degraded", result.getModelTier());
        assertTrue(result.isDegraded());
        fixture.server.verify();
    }

    private TestFixture newFixture(java.util.function.Supplier<String> apiKeySupplier) {
        ControlPanelService controlPanelService = mock(ControlPanelService.class);
        when(controlPanelService.getPrompt()).thenReturn("你是审核助手，只返回JSON。");

        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        AiAnalysisService service = new AiAnalysisService(
                restClientBuilder.build(),
                new ObjectMapper(),
                controlPanelService,
                apiKeySupplier
        );

        ReflectionTestUtils.setField(service, "provider", "deepseek");
        ReflectionTestUtils.setField(service, "promptVersion", "v1");
        ReflectionTestUtils.setField(service, "blockThreshold", 0.8d);
        ReflectionTestUtils.setField(service, "passThreshold", 0.3d);
        ReflectionTestUtils.setField(service, "lightweightUrl", SCORE_URL);
        ReflectionTestUtils.setField(service, "deepseekUrl", LLM_URL);
        ReflectionTestUtils.setField(service, "dotenvFallbackEnabled", false);
        return new TestFixture(service, server);
    }

    private record TestFixture(AiAnalysisService service, MockRestServiceServer server) {
    }
}
