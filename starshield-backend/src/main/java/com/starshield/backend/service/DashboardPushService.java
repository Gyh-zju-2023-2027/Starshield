package com.starshield.backend.service;

import com.starshield.backend.config.DashboardWebSocketHandler;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 大屏指标推送服务。
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class DashboardPushService {

    private final DashboardWebSocketHandler webSocketHandler;
    private final DashboardPayloadService dashboardPayloadService;

    public DashboardPushService(DashboardWebSocketHandler webSocketHandler,
                                DashboardPayloadService dashboardPayloadService) {
        this.webSocketHandler = webSocketHandler;
        this.dashboardPayloadService = dashboardPayloadService;
    }

    /**
     * 定时广播指标。
     *
     * @author AI (under P9 supervision)
     */
    @Scheduled(fixedDelayString = "${starshield.dashboard.push-interval-ms:5000}")
    public void pushMetrics() {
        try {
            webSocketHandler.broadcast(dashboardPayloadService.metricsPayload());
        } catch (Exception ignored) {
        }
    }
}
