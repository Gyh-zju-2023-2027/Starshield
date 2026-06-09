package com.starshield.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.common.Result;
import com.starshield.backend.config.RabbitMQConfig;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import com.starshield.backend.dto.ChatMessageUploadRequest;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.service.IngestionRateLimiterService;
import com.starshield.backend.service.StarshieldMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 玩家发言数据接收控制器。
 */
@RestController
@RequestMapping("/api/chat")
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.INGEST})
public class ChatMessageController {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageController.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final IngestionRateLimiterService rateLimiterService;
    private final StarshieldMetrics metrics;

    public ChatMessageController(RabbitTemplate rabbitTemplate,
                                 ObjectMapper objectMapper,
                                 IngestionRateLimiterService rateLimiterService,
                                 StarshieldMetrics metrics) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.rateLimiterService = rateLimiterService;
        this.metrics = metrics;
    }

    /**
     * 玩家发言数据上传接口。
     *
     * @author AI (under P2 supervision)
     */
    @PostMapping("/upload")
    public Result<Void> upload(@Valid @RequestBody ChatMessageUploadRequest request, HttpServletRequest httpRequest) {
        metrics.recordIngestRequest("attempt");
        String playerId = request.getPlayerId();
        String clientIp = extractClientIp(httpRequest);

        if (!rateLimiterService.allowGlobal()) {
            metrics.recordRateLimited("global");
            return Result.error(429, "全局限流触发，请稍后重试");
        }
        if (!rateLimiterService.allowIp(clientIp)) {
            metrics.recordRateLimited("ip");
            return Result.error(429, "IP 请求过于频繁，请稍后重试");
        }
        if (!rateLimiterService.allowPlayer(playerId)) {
            metrics.recordRateLimited("player");
            return Result.error(429, "玩家请求过于频繁，请稍后重试");
        }

        ChatMessageLog message = new ChatMessageLog()
                .setPlayerId(request.getPlayerId())
                .setContent(request.getContent())
                .setPlatform(request.getPlatform().name());
        if (request.getCreateTime() != null) {
            message.setCreateTime(request.getCreateTime());
        }

        try {
            message.setStatus(0);
            String messageJson = objectMapper.writeValueAsString(message);
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CHAT_EXCHANGE,
                    RabbitMQConfig.CHAT_ROUTING_KEY,
                    messageJson,
                    mqMessage -> {
                        mqMessage.getMessageProperties().setHeader(StarshieldMetrics.ENQUEUED_AT_HEADER, System.currentTimeMillis());
                        return mqMessage;
                    }
            );
            metrics.recordMqPublished();
            metrics.recordIngestRequest("accepted");
            log.debug("[消息入队] playerId={}, ip={}", playerId, clientIp);
        } catch (JsonProcessingException e) {
            metrics.recordIngestRequest("json_error");
            log.error("[消息入队] JSON 序列化失败", e);
            return Result.error("消息格式异常，请检查请求参数");
        } catch (Exception e) {
            metrics.recordMqPublishError();
            metrics.recordIngestRequest("mq_error");
            log.error("[消息入队] 投递 MQ 失败", e);
            return Result.error("消息接收失败，请稍后重试");
        }

        return Result.success("接收成功", null);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
