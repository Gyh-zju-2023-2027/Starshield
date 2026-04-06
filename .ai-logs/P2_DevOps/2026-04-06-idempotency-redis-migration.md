## 阶段日志（增量）
- **日期**：2026-04-06
- **角色**：P2_DevOps
- **主题**：幂等键从内存迁移至 Redis

## 本次新增
1. 新增 `IdempotencyService`，基于 Redis 管理幂等键：
   - 生成 key：`createKey()`
   - 消费 key：`consumeKey()`
   - TTL：5分钟
2. `AdminModerationController` 已移除内存 `ConcurrentHashMap` 存储，改为 Redis 单次消费。

## 价值
- 支持多实例部署下的幂等一致性。
- 避免实例重启导致幂等状态丢失。
