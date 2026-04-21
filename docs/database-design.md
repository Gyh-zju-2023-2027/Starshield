# 数据库设计（StarShield）

本文描述 **MySQL 业务库** 的表结构、索引与初始化方式，并与实体类 `com.starshield.backend.entity` 对应。权威 DDL 见仓库内 `starshield-backend/src/main/resources/init.sql`。

## 库与字符集

| 项 | 值 |
|---|---|
| 库名 | `starshield` |
| 字符集 | `utf8mb4` |
| 排序规则 | `utf8mb4_unicode_ci` |

执行前需已安装 MySQL，并具备建库权限。脚本内包含 `CREATE DATABASE IF NOT EXISTS` 与 `USE starshield`。

## ER 关系（逻辑）

```
chat_message_log (1) ----< (N) moderation_audit_log
        ↑ message_id 指向发言记录主键 id（逻辑外键，DDL 未声明 CONSTRAINT）
```

## 表：`chat_message_log`

玩家发言与机审结果的主表，单行对应一条发言及其最新决策快照。

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 雪花 ID；与 MyBatis-Plus `IdType.ASSIGN_ID` 一致 |
| `player_id` | VARCHAR(64) | NOT NULL | 玩家 ID |
| `content` | TEXT | NOT NULL | 原始发言 |
| `platform` | VARCHAR(32) | NOT NULL | 来源平台（如 `GAME_INNER`、`BILIBILI`） |
| `status` | TINYINT | NOT NULL，默认 0 | `0` 待处理、`1` 正常、`2` 非 PASS（含 REVIEW/BLOCK，见业务文档） |
| `decision` | VARCHAR(16) | 默认 `PASS` | `PASS` / `REVIEW` / `BLOCK` |
| `risk_score` | INT | 默认 0 | 风险分 0–100 |
| `labels` | VARCHAR(255) | 可空 | 标签，逗号分隔 |
| `hit_words` | VARCHAR(255) | 可空 | 引擎 A 命中词，逗号分隔 |
| `ai_analysis_result` | TEXT | 可空 | 引擎 B 结果 JSON（或跳过深检占位 JSON） |
| `create_time` | DATETIME(3) | NOT NULL，默认当前时间 | 创建时间（毫秒精度） |

### 索引

| 索引名 | 列 | 用途 |
|---|---|---|
| PRIMARY | `id` | 主键 |
| `idx_platform` | `platform` | 按平台统计/筛选 |
| `idx_player_id` | `player_id` | 玩家历史 |
| `idx_status` | `status` | 按状态筛选 |
| `idx_decision` | `decision` | 按决策筛选 |
| `idx_risk_score` | `risk_score` | 按风险排序 |
| `idx_create_time` | `create_time` | 时间范围报表 |

## 表：`moderation_audit_log`

人工复核操作审计，保证「谁、何时、对哪条发言、改了什么」可追溯。

| 列名 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | BIGINT | PK | 雪花 ID |
| `message_id` | BIGINT | NOT NULL | 关联 `chat_message_log.id` |
| `operator` | VARCHAR(64) | NOT NULL | 操作人标识 |
| `action` | VARCHAR(32) | NOT NULL | 如 `CONFIRM_BAN`、`RELEASE` |
| `before_decision` | VARCHAR(16) | 可空 | 变更前 `decision` |
| `after_decision` | VARCHAR(16) | 可空 | 变更后 `decision` |
| `before_risk_score` | INT | 可空 | 变更前风险分 |
| `after_risk_score` | INT | 可空 | 变更后风险分 |
| `create_time` | DATETIME(3) | NOT NULL | 操作时间 |

### 索引

| 索引名 | 列 |
|---|---|
| PRIMARY | `id` |
| `idx_message_id` | `message_id` |
| `idx_operator` | `operator` |
| `idx_create_time` | `create_time` |

## 与代码的映射

- 表名与 Java 实体：`ChatMessageLog` → `chat_message_log`，`ModerationAuditLog` → `moderation_audit_log`。
- 列名使用下划线；MyBatis-Plus 开启 `map-underscore-to-camel-case`，与实体驼峰字段互转。

## 相关非 MySQL 存储（便于联调）

以下内容 **不在** `init.sql` 中，但与本项目数据面相关：

| 存储 | 用途 |
|---|---|
| **Redis** | 敏感词集合 `starshield:rules:sensitive_words`；Prompt 多键（如 `starshield:ai:prompt:current`） |
| **Elasticsearch** | 可选归档索引（`starshield.archive.es-enabled`）；文档结构见 `ChatMessageIndex` |

详见 `docs/architecture.md` 与 `docs/business-logic.md`。

## 参考文档

- 字段语义补充：`docs/field-dictionary.md`
- API 与表字段对齐：`docs/api-spec.yaml`
