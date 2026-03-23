package com.starshield.backend.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.common.Result;
import com.starshield.backend.config.RabbitMQConfig;
import com.starshield.backend.entity.ChatMessageLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 玩家发言数据接收控制器
 *
 * =====================================================================
 * 【核心架构要点 - 高并发削峰填谷】
 *
 * 本接口是整个系统的「流量入口」，设计原则是：
 *   接收请求 → 序列化为 JSON → 投入 MQ → 立即返回
 *
 * 绝对禁止在此接口中：
 *   x 直接操作数据库（同步写库会成为高并发瓶颈）
 *   x 调用 AI 接口（AI 调用耗时长，严重影响接口 QPS）
 *   x 执行任何耗时的同步 IO 操作
 *
 * 通过将数据异步投入 RabbitMQ，本接口理论 QPS 可达万级以上，
 * 实际消费速度由下游 ChatMessageConsumer 按照 DB 承压能力平滑消费。
 * =====================================================================
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*") // 允许前端跨域调用（生产环境应配置具体域名）
public class ChatMessageController {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageController.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public ChatMessageController(RabbitTemplate rabbitTemplate, ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 玩家发言数据上传接口
     *
     * 【接口规范】
     * - Method: POST
     * - Path:   /api/chat/upload
     * - Body:   JSON 格式的 ChatMessageLog 对象
     * - 响应时间目标：< 10ms（仅做 MQ 投递，无 DB/AI 阻塞）
     *
     * @param message 玩家发言数据（Spring 自动将请求体 JSON 反序列化为实体对象）
     * @return 统一响应格式，code=200 表示已成功接收并入队
     */
    @PostMapping("/upload")
    public Result<Void> upload(@RequestBody ChatMessageLog message) {
        log.debug("[消息接收] 收到玩家发言 - playerId: {}, platform: {}",
                message.getPlayerId(), message.getPlatform());

        try {
            // Step 1: 将接收到的实体对象序列化为 JSON 字符串
            //         使用 JSON 而非 Java 原生序列化：可读性强、跨语言兼容、便于排查问题
            String messageJson = objectMapper.writeValueAsString(message);

            // Step 2: 将 JSON 消息投递到 RabbitMQ 交换机
            //   - exchange:   目标交换机（路由中心）
            //   - routingKey: 路由键，交换机据此将消息分发到绑定的队列
            //   - message:    消息体（JSON 字符串）
            //
            //   此操作极快（网络 RTT 级别），不阻塞当前线程，
            //   消息持久化到 MQ 后即可立刻给前端返回 200。
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.CHAT_EXCHANGE,
                    RabbitMQConfig.CHAT_ROUTING_KEY,
                    messageJson
            );

            log.debug("[消息入队] 成功投递到 MQ - playerId: {}", message.getPlayerId());

        } catch (JsonProcessingException e) {
            // JSON 序列化异常（正常情况下不会触发）
            log.error("[消息入队] JSON 序列化失败 - error: {}", e.getMessage(), e);
            return Result.error("消息格式异常，请检查请求参数");
        } catch (Exception e) {
            // MQ 连接异常等其他错误
            log.error("[消息入队] 投递 MQ 失败 - error: {}", e.getMessage(), e);
            return Result.error("消息接收失败，请稍后重试");
        }

        // Step 3: 立即返回成功响应，不等待消费者处理结果
        //         这是削峰填谷的关键：生产者和消费者完全解耦
        return Result.success("接收成功", null);
    }
}
