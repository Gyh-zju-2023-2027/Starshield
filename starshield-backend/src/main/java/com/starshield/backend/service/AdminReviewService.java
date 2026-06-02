package com.starshield.backend.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starshield.backend.entity.ChatMessageLog;
import com.starshield.backend.entity.ModerationAuditLog;
import com.starshield.backend.config.runtime.EnabledOnMode;
import com.starshield.backend.config.runtime.RuntimeMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * 审核后台服务。
 */
@Service
@EnabledOnMode({RuntimeMode.MONOLITH, RuntimeMode.API})
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
    public List<ModerationAuditLog> queryAuditLogs(Long messageId, String reasonTag, Integer limit) {
        LambdaQueryWrapper<ModerationAuditLog> wrapper = new LambdaQueryWrapper<ModerationAuditLog>()
                .eq(ModerationAuditLog::getMessageId, messageId);
        if (reasonTag != null && !reasonTag.isBlank()) {
            wrapper.eq(ModerationAuditLog::getReasonTag, reasonTag);
        }
        return moderationAuditLogService.list(wrapper
                .orderByDesc(ModerationAuditLog::getCreateTime)
                .last("limit " + limit));
    }

    /**
     * 确认封禁。
     *
     * @author AI (under P5 supervision)
     */
    public boolean confirmBan(Long id, String operator, String reasonTag) {
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
        if (reasonTag != null && !reasonTag.isBlank()) {
            target.setReasonTag(reasonTag);
        }
        boolean updated = chatMessageService.updateById(target);

        if (updated) {
            saveAudit(id, operator, "CONFIRM_BAN", beforeDecision, target.getDecision(), beforeRisk, target.getRiskScore(), reasonTag);
        }

        return updated;
    }

    public boolean confirmBan(Long id, String operator) {
        return confirmBan(id, operator, null);
    }

    /**
     * 解除封禁/判定正常。
     *
     * @author AI (under P5 supervision)
     */
    public boolean release(Long id, String operator, String reasonTag) {
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
        if (reasonTag != null && !reasonTag.isBlank()) {
            target.setReasonTag(reasonTag);
        }
        boolean updated = chatMessageService.updateById(target);

        if (updated) {
            saveAudit(id, operator, "RELEASE", beforeDecision, target.getDecision(), beforeRisk, target.getRiskScore(), reasonTag);
        }

        return updated;
    }

    public boolean release(Long id, String operator) {
        return release(id, operator, null);
    }

    /**
     * 加入观察名单。
     *
     * @author AI (under P5 supervision)
     */
    public boolean markReview(Long id, String operator, String reasonTag) {
        ChatMessageLog target = chatMessageService.getById(id);
        if (target == null) {
            return false;
        }

        String beforeDecision = target.getDecision();
        Integer beforeRisk = target.getRiskScore();

        target.setDecision("REVIEW");
        target.setStatus(0);
        String appendLabel = target.getLabels() == null ? "manual_review" : target.getLabels() + ",manual_review";
        target.setLabels(appendLabel);
        if (reasonTag != null && !reasonTag.isBlank()) {
            target.setReasonTag(reasonTag);
        }
        boolean updated = chatMessageService.updateById(target);

        if (updated) {
            saveAudit(id, operator, "MARK_REVIEW", beforeDecision, target.getDecision(), beforeRisk, target.getRiskScore(), reasonTag);
        }

        return updated;
    }

    /**
     * 批量处理。
     *
     * @author AI (under P5 supervision)
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, String> batchProcess(List<Long> ids, String decision, String reasonTag, String operator) {
        Map<Long, String> failures = new HashMap<>();
        for (Long id : ids) {
            try {
                boolean success = false;
                if ("BLOCK".equals(decision)) {
                    success = confirmBan(id, operator, reasonTag);
                } else if ("PASS".equals(decision)) {
                    success = release(id, operator, reasonTag);
                } else if ("REVIEW".equals(decision)) {
                    success = markReview(id, operator, reasonTag);
                }
                if (!success) {
                    failures.put(id, "处理失败或记录不存在");
                }
            } catch (Exception e) {
                failures.put(id, e.getMessage() != null ? e.getMessage() : "未知错误");
            }
        }
        return failures;
    }

    private void saveAudit(Long messageId,
                           String operator,
                           String action,
                           String beforeDecision,
                           String afterDecision,
                           Integer beforeRisk,
                           Integer afterRisk,
                           String reasonTag) {
        ModerationAuditLog auditLog = new ModerationAuditLog()
                .setMessageId(messageId)
                .setOperator(operator == null || operator.isBlank() ? "system" : operator)
                .setAction(action)
                .setBeforeDecision(beforeDecision)
                .setAfterDecision(afterDecision)
                .setBeforeRiskScore(beforeRisk)
                .setAfterRiskScore(afterRisk)
                .setReasonTag(reasonTag)
                .setCreateTime(LocalDateTime.now());
        moderationAuditLogService.save(auditLog);
    }
}
