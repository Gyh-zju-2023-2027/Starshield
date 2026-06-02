package com.starshield.backend.config;

import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置。
 */
@Configuration
@EnableWebSocket
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class WebSocketConfig implements WebSocketConfigurer {

    private final DashboardWebSocketHandler dashboardWebSocketHandler;

    public WebSocketConfig(DashboardWebSocketHandler dashboardWebSocketHandler) {
        this.dashboardWebSocketHandler = dashboardWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(dashboardWebSocketHandler, "/ws/dashboard")
                .setAllowedOriginPatterns("*");
    }
}
