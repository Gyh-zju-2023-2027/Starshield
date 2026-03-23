package com.starshield.backend.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.starshield.backend.config.RabbitMQConfig;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.service.AiAnalysisService;
import com.starshield.backend.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 玩家发言消息消费者
 *
 * =====================================================================
 * 【核心架构要点 - 异步落盘】
 *
 * 本类监听 RabbitMQ 队列，异步消费消息，主要职责：
 *   1. 从队列中取出消息并解析为实体对象
 *   2. 调用 AI 分析服务获取内容审核结果（当前为预留实现）
 *   3. 将带有 AI 分析结果的完整记录写入 MySQL
 *   4. 手动 ACK 确认消费成功（防止消息丢失）
 *
 * 消费并发度由 application.yml 中 listener.simple.concurrency 控制，
 * 当前配置为 5~20 个并发消费者线程，可根据 DB 压力动态调整。
 * =====================================================================
 */
@Component
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final AiAnalysisService aiAnalysisService;
    private final ChatMessageService chatMessageService;

    public ChatMessageConsumer(ObjectMapper objectMapper,
                               AiAnalysisService aiAnalysisService,
                               ChatMessageService chatMessageService) {
        this.objectMapper = objectMapper;
        this.aiAnalysisService = aiAnalysisService;
        this.chatMessageService = chatMessageService;
    }

    /**
     * 监听并消费玩家发言消息
     *
     * @param messageBody MQ 消息体（JSON 字符串）
     * @param message     RabbitMQ 原始 Message 对象（含元数据，用于 ACK）
     * @param channel     RabbitMQ Channel（用于手动 ACK/NACK）
     */
    @RabbitListener(queues = RabbitMQConfig.CHAT_MESSAGE_QUEUE)
    public void consume(String messageBody, Message message, Channel channel) {
        // 获取消息的唯一标识（用于 ACK/NACK）
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        log.debug("[消息消费] 开始处理消息 - deliveryTag: {}", deliveryTag);

        try {
            // ----------------------------------------------------------------
            // Step 1: 将 JSON 字符串反序列化为实体对象
            // ----------------------------------------------------------------
            ChatMessageLog chatLog = objectMapper.readValue(messageBody, ChatMessageLog.class);
            log.debug("[消息消费] 反序列化成功 - playerId: {}, platform: {}",
                    chatLog.getPlayerId(), chatLog.getPlatform());

            // ----------------------------------------------------------------
            // Step 2: 调用 AI 分析服务（当前为预留实现，返回"正常"）
            //
            // 【扩展说明】
            // 后续接入真实大模型时，此处可能耗时 100ms~3s，
            // 但由于本方法在独立消费者线程中运行，不影响上游接收接口的 QPS。
            // 如需进一步提升吞吐，可将 AI 调用也做成异步，分两阶段写库。
            // ----------------------------------------------------------------
            String aiResult = aiAnalysisService.analyze(chatLog.getContent());
            log.debug("[AI分析] 分析完成 - playerId: {}, result: {}", chatLog.getPlayerId(), aiResult);

            // ----------------------------------------------------------------
            // Step 3: 根据 AI 分析结果设置状态并写入数据库
            // ----------------------------------------------------------------
            // 设置 AI 分析结果
            chatLog.setAiAnalysisResult(aiResult);

            // 根据 AI 结果设置状态：当前预留实现默认为「正常(1)」
            // 后续接入真实 AI 时，根据模型返回的 label 动态判断：
            //   label == "违规" -> status = 2
            //   label == "正常" -> status = 1
            chatLog.setStatus(1); // 1 = 正常

            // 通过 MyBatis-Plus 写入 MySQL
            // 注：createTime 由 MyBatisPlusConfig 自动填充，无需手动设置
            boolean saved = chatMessageService.save(chatLog);

            if (saved) {
                log.debug("[落盘成功] 记录已写入 MySQL - id: {}, playerId: {}",
                        chatLog.getId(), chatLog.getPlayerId());
            } else {
                log.warn("[落盘异常] save 返回 false - playerId: {}", chatLog.getPlayerId());
            }

            // ----------------------------------------------------------------
            // Step 4: 手动 ACK —— 通知 RabbitMQ 消息已成功处理，可从队列中移除
            //
            // 若不 ACK，消息会在消费者断开后重新入队，导致重复消费。
            // 这是「至少一次投递（At Least Once）」语义的关键保障。
            // ----------------------------------------------------------------
            channel.basicAck(deliveryTag, false);
            log.debug("[消息消费] ACK 成功 - deliveryTag: {}", deliveryTag);

        } catch (Exception e) {
            log.error("[消息消费] 处理失败 - deliveryTag: {}, error: {}", deliveryTag, e.getMessage(), e);
            try {
                // NACK 并且不重新入队（requeue=false），消息将进入死信队列
                // 避免因持续重试导致队列堵塞（"毒消息"问题）
                channel.basicNack(deliveryTag, false, false);
                log.warn("[消息消费] NACK 已发送，消息转入死信队列 - deliveryTag: {}", deliveryTag);
            } catch (IOException ioException) {
                log.error("[消息消费] NACK 发送失败 - deliveryTag: {}, error: {}",
                        deliveryTag, ioException.getMessage(), ioException);
            }
        }
    }
}
