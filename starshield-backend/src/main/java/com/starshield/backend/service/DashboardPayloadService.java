package com.starshield.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.common.Result;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.stereotype.Service;

/**
 * Builds the dashboard WebSocket payload shared by initial and scheduled pushes.
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class DashboardPayloadService {

    private final DashboardControllerSupport dashboardControllerSupport;
    private final ObjectMapper objectMapper;

    public DashboardPayloadService(DashboardControllerSupport dashboardControllerSupport,
                                   ObjectMapper objectMapper) {
        this.dashboardControllerSupport = dashboardControllerSupport;
        this.objectMapper = objectMapper;
    }

    public String metricsPayload() throws JsonProcessingException {
        Result<?> result = dashboardControllerSupport.metrics();
        return objectMapper.writeValueAsString(result);
    }
}
