package com.starshield.backend.config;

import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import com.starshield.backend.service.DashboardPayloadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大屏 WebSocket 处理器。
 */
@Component
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
public class DashboardWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final DashboardPayloadService dashboardPayloadService;

    public DashboardWebSocketHandler(DashboardPayloadService dashboardPayloadService) {
        this.dashboardPayloadService = dashboardPayloadService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        try {
            send(session, dashboardPayloadService.metricsPayload());
        } catch (Exception e) {
            log.warn("[大屏WS] 首屏快照推送失败 sessionId={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(String payload) {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                send(session, payload);
            }
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (Exception e) {
            log.debug("[大屏WS] 推送失败 sessionId={}", session.getId(), e);
        }
    }
}
