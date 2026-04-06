package com.starshield.backend.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.starshield.backend.config.RabbitMQConfig;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.model.AiModerationResult;
import com.starshield.backend.model.FastCheckResult;
import com.starshield.backend.model.ModerationDecision;
import com.starshield.backend.service.AiAnalysisService;
import com.starshield.backend.service.ArchiveSyncService;
import com.starshield.backend.service.ChatMessageService;
import com.starshield.backend.service.RuleEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 玩家发言消息消费者。
 */
@Component
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final ObjectMapper objectMapper;
    private final AiAnalysisService aiAnalysisService;
    private final ChatMessageService chatMessageService;
    private final RuleEngineService ruleEngineService;
    private final ArchiveSyncService archiveSyncService;

    public ChatMessageConsumer(ObjectMapper objectMapper,
                               AiAnalysisService aiAnalysisService,
                               ChatMessageService chatMessageService,
                               RuleEngineService ruleEngineService,
                               ArchiveSyncService archiveSyncService) {
        this.objectMapper = objectMapper;
        this.aiAnalysisService = aiAnalysisService;
        this.chatMessageService = chatMessageService;
        this.ruleEngineService = ruleEngineService;
        this.archiveSyncService = archiveSyncService;
    }

    /**
     * 监听并消费玩家发言消息。
     *
     * @author AI (under P3/P4 supervision)
     */
    @RabbitListener(queues = RabbitMQConfig.CHAT_MESSAGE_QUEUE)
    public void consume(String messageBody, Message message, Channel channel) {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            ChatMessageLog chatLog = objectMapper.readValue(messageBody, ChatMessageLog.class);

            FastCheckResult fastResult = ruleEngineService.fastCheck(chatLog.getContent());
            String decision = fastResult.getDecision();
            int riskScore = fastResult.getRiskScore();
            String labels = fastResult.getLabels();
            String reason = fastResult.getReason();

            if (!ModerationDecision.BLOCK.equals(decision)) {
                AiModerationResult aiResult = aiAnalysisService.analyze(chatLog.getContent());
                if (aiResult.getRiskScore() > riskScore) {
                    riskScore = aiResult.getRiskScore();
                }
                decision = mergeDecision(decision, aiResult.getDecision());
                labels = mergeLabels(labels, aiResult.getLabels());
                reason = reason + " | AI:" + aiResult.getReason();

                String aiJson = objectMapper.writeValueAsString(aiResult);
                chatLog.setAiAnalysisResult(aiJson);
            } else {
                chatLog.setAiAnalysisResult("{\"engine\":\"A\",\"skippedDeepCheck\":true}");
            }

            chatLog.setDecision(decision);
            chatLog.setRiskScore(riskScore);
            chatLog.setLabels(labels);
            chatLog.setHitWords(fastResult.getHitWords());
            chatLog.setStatus(toStatus(decision));

            chatMessageService.save(chatLog);
            archiveSyncService.syncToEs(chatLog);

            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("[消息消费] 处理失败 deliveryTag={}", deliveryTag, e);
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackEx) {
                log.error("[消息消费] NACK 失败 deliveryTag={}", deliveryTag, nackEx);
            }
        }
    }

    private int toStatus(String decision) {
        return ModerationDecision.PASS.equals(decision) ? 1 : 2;
    }

    private String mergeDecision(String d1, String d2) {
        if (ModerationDecision.BLOCK.equals(d1) || ModerationDecision.BLOCK.equals(d2)) {
            return ModerationDecision.BLOCK;
        }
        if (ModerationDecision.REVIEW.equals(d1) || ModerationDecision.REVIEW.equals(d2)) {
            return ModerationDecision.REVIEW;
        }
        return ModerationDecision.PASS;
    }

    private String mergeLabels(String labels1, String labels2) {
        if (labels1 == null || labels1.isBlank()) {
            return labels2;
        }
        if (labels2 == null || labels2.isBlank()) {
            return labels1;
        }
        if (labels1.contains(labels2)) {
            return labels1;
        }
        if (labels2.contains(labels1)) {
            return labels2;
        }
        return labels1 + "," + labels2;
    }
}
