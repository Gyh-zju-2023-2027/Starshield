package com.starshield.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审核操作审计日志。
 */
@Data
@Accessors(chain = true)
@TableName("moderation_audit_log")
public class ModerationAuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long messageId;

    private String operator;

    private String action;

    private String beforeDecision;

    private String afterDecision;

    private Integer beforeRiskScore;

    private Integer afterRiskScore;

    private LocalDateTime createTime;
}
