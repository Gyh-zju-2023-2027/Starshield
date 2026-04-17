package com.starshield.backend.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 玩家发言记录实体类
 * <p>
 * 对应数据库表：chat_message_log
 * 该表承载核心舆情数据，预计单日写入量可达亿级别，
 * 主键采用雪花算法保证分布式唯一性且有序递增。
 */
@Data
@Accessors(chain = true) // 支持链式调用，提升代码可读性
@TableName("chat_message_log")
public class ChatMessageLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID，使用雪花算法生成（分布式唯一、时间有序）
     */
    @TableId(type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 玩家 ID（来自游戏平台）
     */
    private String playerId;

    /**
     * 发言内容（原始文本）
     */
    private String content;

    /**
     * 数据来源平台，如：BILIBILI / GAME_INNER / WEIBO 等
     */
    private String platform;

    /**
     * 审核状态：
     * 0 - 待处理（消息刚入库，尚未经过 AI 分析）
     * 1 - 正常
     * 2 - 违规
     */
    private Integer status;

    /**
     * 审核决策：PASS / REVIEW / BLOCK
     */
    private String decision;

    /**
     * 风险分（0-100）
     */
    private Integer riskScore;

    /**
     * 标签（逗号分隔）
     */
    private String labels;

    /**
     * 命中词（逗号分隔）
     */
    private String hitWords;

    /**
     * AI 大模型分析结果（JSON 字符串格式）
     * 例如：{"label":"正常","confidence":0.98,"reason":"无违禁词"}
     * 初始为空，待 AI 模块异步填充
     */
    private String aiAnalysisResult;

    /**
     * 记录创建时间（消息入库时间）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
