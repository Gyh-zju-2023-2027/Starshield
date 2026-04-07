## 阶段日志
- **日期**：2026-04-06
- **角色**：P6_ES_Search
- **任务**：百万级发言检索中台（MySQL 检索 + ES 双写骨架）

## 1. 核心提示词 (Prompt)
"请实现归档检索 API（多条件过滤）、ES 索引模型与 Repository、以及在消费落库后的双写同步服务（可配置开关），读路径可先走 MySQL 保证快速可用。"

## 已完成
1. 新增检索 API：
   - `GET /api/archive/search`
   - 支持 `keyword/playerId/decision/labels/startTime/endTime/limit` 组合过滤。
2. 新增 ES 归档模型与仓储：
   - `ChatMessageIndex`
   - `ChatMessageIndexRepository`
3. 新增 `ArchiveSyncService`，在消费落库后执行双写同步（可配置开关 `starshield.archive.es-enabled`）。

## 变更文件
- `starshield-backend/src/main/java/com/starshield/backend/controller/ArchiveSearchController.java`
- `starshield-backend/src/main/java/com/starshield/backend/archive/ChatMessageIndex.java`
- `starshield-backend/src/main/java/com/starshield/backend/archive/ChatMessageIndexRepository.java`
- `starshield-backend/src/main/java/com/starshield/backend/service/ArchiveSyncService.java`
- `starshield-backend/src/main/java/com/starshield/backend/consumer/ChatMessageConsumer.java`

## 下一步
- 切换检索读路径到 ES DSL（当前接口先走 MySQL，保障快速可用）。
- 增加失败补偿与重放机制（DLQ -> replay job）。
