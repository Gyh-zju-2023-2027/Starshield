package com.starshield.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.entity.ModerationAuditLog;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 审核后台服务。
 */
@Service
public class AdminReviewService {

    private final ChatMessageService chatMessageService;
    private final ModerationAuditLogService moderationAuditLogService;

    public AdminReviewService(ChatMessageService chatMessageService,
                              ModerationAuditLogService moderationAuditLogService) {
        this.chatMessageService = chatMessageService;
        this.moderationAuditLogService = moderationAuditLogService;
    }

    /**
     * 查询待复核记录。
     *
     * @author AI (under P5 supervision)
     */
    public List<ChatMessageLog> queryPending(Integer page, Integer pageSize) {
        int offset = Math.max(page - 1, 0) * pageSize;
        return chatMessageService.list(new LambdaQueryWrapper<ChatMessageLog>()
                .eq(ChatMessageLog::getDecision, "REVIEW")
                .orderByDesc(ChatMessageLog::getRiskScore)
                .last("limit " + offset + "," + pageSize));
    }

    /**
     * 查询某条记录的审计日志。
     *
     * @author AI (under P5 supervision)
     */
    public List<ModerationAuditLog> queryAuditLogs(Long messageId, Integer limit) {
        return moderationAuditLogService.list(new LambdaQueryWrapper<ModerationAuditLog>()
                .eq(ModerationAuditLog::getMessageId, messageId)
                .orderByDesc(ModerationAuditLog::getCreateTime)
                .last("limit " + limit));
    }

    /**
     * 确认封禁。
     *
     * @author AI (under P5 supervision)
     */
    public boolean confirmBan(Long id, String operator) {
        ChatMessageLog target = chatMessageService.getById(id);
        if (target == null) {
            return false;
        }

        String beforeDecision = target.getDecision();
        Integer beforeRisk = target.getRiskScore();

        target.setDecision("BLOCK");
        target.setStatus(2);
        target.setRiskScore(Math.max(90, target.getRiskScore() == null ? 0 : target.getRiskScore()));
        String appendLabel = target.getLabels() == null ? "manual_ban" : target.getLabels() + ",manual_ban";
        target.setLabels(appendLabel);
        boolean updated = chatMessageService.updateById(target);

        if (updated) {
            saveAudit(id, operator, "CONFIRM_BAN", beforeDecision, target.getDecision(), beforeRisk, target.getRiskScore());
        }

        return updated;
    }

    /**
     * 解除封禁/判定正常。
     *
     * @author AI (under P5 supervision)
     */
    public boolean release(Long id, String operator) {
        ChatMessageLog target = chatMessageService.getById(id);
        if (target == null) {
            return false;
        }

        String beforeDecision = target.getDecision();
        Integer beforeRisk = target.getRiskScore();

        target.setDecision("PASS");
        target.setStatus(1);
        target.setRiskScore(Math.min(30, target.getRiskScore() == null ? 30 : target.getRiskScore()));
        String appendLabel = target.getLabels() == null ? "manual_release" : target.getLabels() + ",manual_release";
        target.setLabels(appendLabel);
        boolean updated = chatMessageService.updateById(target);

        if (updated) {
            saveAudit(id, operator, "RELEASE", beforeDecision, target.getDecision(), beforeRisk, target.getRiskScore());
        }

        return updated;
    }

    private void saveAudit(Long messageId,
                           String operator,
                           String action,
                           String beforeDecision,
                           String afterDecision,
                           Integer beforeRisk,
                           Integer afterRisk) {
        ModerationAuditLog auditLog = new ModerationAuditLog()
                .setMessageId(messageId)
                .setOperator(operator == null || operator.isBlank() ? "system" : operator)
                .setAction(action)
                .setBeforeDecision(beforeDecision)
                .setAfterDecision(afterDecision)
                .setBeforeRiskScore(beforeRisk)
                .setAfterRiskScore(afterRisk)
                .setCreateTime(LocalDateTime.now());
        moderationAuditLogService.save(auditLog);
    }
}
