package com.starshield.backend.config;

import com.starshield.backend.common.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResultHttpStatusAdviceTest {

    private final ResultHttpStatusAdvice advice = new ResultHttpStatusAdvice();

    @Test
    void shouldMapErrorCodeToHttpStatus() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        advice.beforeBodyWrite(
                Result.error(429, "限流"),
                null,
                MediaType.APPLICATION_JSON,
                null,
                new ServletServerHttpRequest(new MockHttpServletRequest("POST", "/api/chat/upload")),
                new ServletServerHttpResponse(servletResponse)
        );

        assertEquals(429, servletResponse.getStatus());
    }

    @Test
    void shouldKeepSuccessfulResponseStatusUntouched() {
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        advice.beforeBodyWrite(
                Result.success(),
                null,
                MediaType.APPLICATION_JSON,
                null,
                new ServletServerHttpRequest(new MockHttpServletRequest("GET", "/api/dashboard/metrics")),
                new ServletServerHttpResponse(servletResponse)
        );

        assertEquals(200, servletResponse.getStatus());
    }
}
