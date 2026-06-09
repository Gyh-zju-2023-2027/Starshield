package com.starshield.backend.config;

import com.starshield.backend.service.DashboardPayloadService;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardWebSocketHandlerTest {

    @Test
    void shouldPushInitialSnapshotWhenConnectionEstablished() throws Exception {
        DashboardPayloadService payloadService = mock(DashboardPayloadService.class);
        when(payloadService.metricsPayload()).thenReturn("{\"code\":200}");
        DashboardWebSocketHandler handler = new DashboardWebSocketHandler(payloadService);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(session).sendMessage(argThat(message ->
                message instanceof TextMessage textMessage
                        && "{\"code\":200}".equals(textMessage.getPayload())));
    }
}
