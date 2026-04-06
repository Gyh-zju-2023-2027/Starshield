package com.starshield.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.common.Result;
import com.starshield.backend.config.DashboardWebSocketHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 大屏指标推送服务。
 */
@Service
public class DashboardPushService {

    private final DashboardWebSocketHandler webSocketHandler;
    private final DashboardControllerSupport dashboardControllerSupport;
    private final ObjectMapper objectMapper;

    public DashboardPushService(DashboardWebSocketHandler webSocketHandler,
                                DashboardControllerSupport dashboardControllerSupport,
                                ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.dashboardControllerSupport = dashboardControllerSupport;
        this.objectMapper = objectMapper;
    }

    /**
     * 定时广播指标。
     *
     * @author AI (under P9 supervision)
     */
    @Scheduled(fixedDelay = 5000)
    public void pushMetrics() {
        try {
            Result<?> result = dashboardControllerSupport.metrics();
            webSocketHandler.broadcast(objectMapper.writeValueAsString(result));
        } catch (Exception ignored) {
        }
    }
}
