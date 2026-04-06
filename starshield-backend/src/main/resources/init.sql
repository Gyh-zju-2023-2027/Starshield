-- ================================================================
-- 星盾 (StarShield) 舆情监控中台 - 数据库初始化脚本
-- 执行前请先创建数据库：CREATE DATABASE starshield CHARACTER SET utf8mb4;
-- ================================================================

CREATE DATABASE IF NOT EXISTS `starshield`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `starshield`;

-- ----------------------------------------------------------------
-- 玩家发言记录表
-- 核心业务表，承载全量舆情数据
-- 预计日增量：亿级；主键采用雪花算法保证分布式唯一且有序
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `chat_message_log`
(
    `id`                 BIGINT       NOT NULL COMMENT '主键ID，雪花算法生成（分布式唯一、时间有序）',
    `player_id`          VARCHAR(64)  NOT NULL COMMENT '玩家ID（来自游戏平台）',
    `content`            TEXT         NOT NULL COMMENT '发言原始内容',
    `platform`           VARCHAR(32)  NOT NULL COMMENT '数据来源平台：BILIBILI / GAME_INNER / WEIBO 等',
    `status`             TINYINT      NOT NULL DEFAULT 0 COMMENT '审核状态：0-待处理 1-正常 2-违规',
    `decision`           VARCHAR(16)           DEFAULT 'PASS' COMMENT '审核决策：PASS/REVIEW/BLOCK',
    `risk_score`         INT                   DEFAULT 0 COMMENT '风险分（0-100）',
    `labels`             VARCHAR(255)          DEFAULT NULL COMMENT '标签（逗号分隔）',
    `hit_words`          VARCHAR(255)          DEFAULT NULL COMMENT '命中词（逗号分隔）',
    `ai_analysis_result` TEXT                  DEFAULT NULL COMMENT 'AI大模型分析结果JSON串，初始为空，待异步填充',
    `create_time`        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录创建时间（精确到毫秒）',
    PRIMARY KEY (`id`),
    -- 按平台查询的索引
    INDEX `idx_platform` (`platform`),
    -- 按玩家ID查询历史记录的索引
    INDEX `idx_player_id` (`player_id`),
    -- 按状态筛选违规/待处理记录的索引
    INDEX `idx_status` (`status`),
    -- 按审核决策筛选
    INDEX `idx_decision` (`decision`),
    -- 按风险分排序
    INDEX `idx_risk_score` (`risk_score`),
    -- 按时间范围查询的索引（运营报表常用）
    INDEX `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '玩家发言记录表 - 星盾舆情监控核心数据表';

-- ----------------------------------------------------------------
-- 审核操作审计表
-- 记录人工复核动作，保障可追溯性
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `moderation_audit_log`
(
    `id`                BIGINT      NOT NULL COMMENT '主键ID，雪花算法',
    `message_id`        BIGINT      NOT NULL COMMENT '关联的发言记录ID',
    `operator`          VARCHAR(64) NOT NULL COMMENT '操作人',
    `action`            VARCHAR(32) NOT NULL COMMENT '动作：CONFIRM_BAN/RELEASE',
    `before_decision`   VARCHAR(16)          DEFAULT NULL COMMENT '操作前决策',
    `after_decision`    VARCHAR(16)          DEFAULT NULL COMMENT '操作后决策',
    `before_risk_score` INT                  DEFAULT NULL COMMENT '操作前风险分',
    `after_risk_score`  INT                  DEFAULT NULL COMMENT '操作后风险分',
    `create_time`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '操作时间',
    PRIMARY KEY (`id`),
    INDEX `idx_message_id` (`message_id`),
    INDEX `idx_operator` (`operator`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '人工复核操作审计日志';
