# 业务逻辑说明（StarShield）

本文按 **真实代码路径** 描述核心业务流程，便于与 `docs/architecture.md`、`docs/database-design.md` 对照。

## 1. 发言接入（同步）

**入口**：`POST /api/chat/upload`（`ChatMessageController`）

1. 从请求体反序列化为 `ChatMessageLog`。
2. **限流**（`IngestionRateLimiterService`）：全局限流、按客户端 IP、按 `playerId`；触发返回 HTTP 语义由 `Result` 封装为 `429`。
3. 将 `status` 置为 `0`（待处理）。
4. 将整个对象 **JSON 序列化后** 发送到 RabbitMQ（`chat.direct.exchange` + `chat.message.routing.key`）。
5. 接口立即返回成功；**此时尚未写 MySQL**，审核在消费者中完成。

## 2. 异步审核流水线（核心）

**消费者**：`ChatMessageConsumer` 监听 `chat.message.queue`，手动 ACK。

### 2.1 引擎 A（规则 / 敏感词）

**服务**：`RuleEngineService.fastCheck`

- 从 Redis Set `starshield:rules:sensitive_words` 加载词表；若为空则使用内置默认词表（与 `ControlPanelService` 展示默认值一致）。
- 对正文做规范化：转小写、去空白、去掉 `*`、`-`。
- 子串包含即命中，收集所有命中词。
- **未命中**：`decision=PASS`，`riskScore=10`，`labels=normal`，`hitWords` 空。
- **命中**：`riskScore = min(95, 55 + 命中数*15)`；`labels=keyword_violation`；`decision` 为 `BLOCK`（risk≥80）或 `REVIEW`（否则）；`reason` 为「命中敏感词」。

### 2.2 引擎 B（轻量分数 + 可选 DeepSeek）

**服务**：`AiAnalysisService.analyze`

1. 调用轻量 HTTP 服务（`starshield.ai.lightweight-url`），请求体 `{"text": content}`，解析 `score`∈[0,1]；失败或缺字段时用 **0.5** 继续。
2. 与配置阈值比较（`starshield.ai.pass-threshold`、`block-threshold`，默认 0.3 / 0.8）：
   - `score ≥ block-threshold`：直接返回 **轻量** 结果，`modelTier=lightweight`，`decision=BLOCK`，`labels=abuse`（等，见代码）。
   - `score < pass-threshold`：直接 **PASS** 轻量结果。
   - 介于两者之间：调用 **DeepSeek** `chat/completions`；无 Key 或调用/解析失败则 **降级**：`decision=REVIEW`，`modelTier=degraded`，`labels=degraded`。
3. Prompt 来源：`ControlPanelService.getPrompt()`（Redis + 默认长模板）；`provider`、`promptVersion` 写入 `AiModerationResult`。

### 2.3 引擎 A 与 B 的合并（消费者内）

- 若引擎 A 已为 **BLOCK**：**跳过** 深度分析，`ai_analysis_result` 固定为 `{"engine":"A","skippedDeepCheck":true}`。
- 否则调用 `AiAnalysisService`，并合并：
  - **riskScore**：取 A 与 B 的较大值（整数比较）。
  - **decision**：`mergeDecision` — 任一为 `BLOCK` 则 `BLOCK`；否则任一为 `REVIEW` 则 `REVIEW`；否则 `PASS`。
  - **labels**：`mergeLabels` — 非空拼接/去重简化规则（见源码）。
  - **reason**：引擎 A 的 `reason` + `" | AI:"` + B 的 `reason`。
  - **ai_analysis_result**：将整个 `AiModerationResult` 序列化为 JSON 字符串。

### 2.4 写库与归档

- `hitWords` 使用引擎 A 的命中串。
- **`status`（`toStatus`）**：仅当 `decision` 为 `PASS` 时为 `1`；**`REVIEW` 与 `BLOCK` 均为 `2`**。注意：这与表注释中「2-违规」的表述在 REVIEW 场景下不完全一致，运营侧应以 `decision` 为准。
- `ChatMessageService.save(chatLog)` 落 MySQL。
- `ArchiveSyncService.syncToEs`：仅当 `starshield.archive.es-enabled=true` 且 Repository 可用时写入 ES。

消费失败：记录错误日志并 NACK（具体是否进 DLQ 由队列与 Broker 策略决定）。

## 3. 运营控制台（热更新）

**前缀**：`/api/control`（`ControlPanelController`）

| 接口 | 行为 |
|---|---|
| `GET/PUT .../rules/sensitive-words` | 读/全量替换 Redis 敏感词，并调用 `RuleEngineService.replaceSensitiveWords` 清本地缓存 |
| `GET/PUT .../prompt` | 读/写当前 Prompt（多 Redis 键兼容版本化与旧键） |

## 4. 人工复核

**前缀**：`/api/admin/moderation`（`AdminModerationController`）

- **待审列表**：`decision = REVIEW`，按 `riskScore` 降序分页。
- **确认封禁** `confirm-ban`：`decision→BLOCK`，`status→2`，风险分拉高，追加标签 `manual_ban`，写审计表。
- **解除** `release`：`decision→PASS`，`status→1`，风险分压低，追加 `manual_release`，写审计表。
- **幂等**：`X-Idempotency-Key` 经 `IdempotencyService` 校验，重复返回 `409`。

审计记录写入 `moderation_audit_log`（见数据库文档）。

## 5. 归档检索

**前缀**：`/api/archive/search`（`ArchiveSearchService`）

- `starshield.archive.es-enabled=true` 且 ES Repository 可用：从索引分页拉取后在内存过滤（关键词、玩家、决策、标签、时间）。
- 否则：**MySQL** `LambdaQueryWrapper` 组合条件 + `limit offset,size`。

## 6. 大屏指标

**接口**：`GET /api/dashboard/metrics`（`DashboardControllerSupport`）

- 从 MySQL 统计总条数、`decision=BLOCK` 数、`decision=REVIEW` 数，计算拦截率。
- `latest`：最近 100 条按创建时间倒序。

（如项目内另有 WebSocket 推送，用于实时刷新大屏，见 `DashboardWebSocketHandler` / `DashboardPushService`。）

## 7. 配置项速查（业务相关）

| 配置前缀 | 含义 |
|---|---|
| `starshield.rate-limit.*` | 接入 QPS 上限 |
| `starshield.archive.es-enabled` | 是否写/查 ES |
| `starshield.ai.*` | 提供方标识、Prompt 版本、轻量 URL、DeepSeek URL、阈值、`.env` 回读开关等 |

完整列表以 `application.yml` 为准。

## 相关文档

- 数据库表与索引：`docs/database-design.md`
- 组件与中间件拓扑：`docs/architecture.md`
- MQ 消息字段：`docs/event-spec.md`
