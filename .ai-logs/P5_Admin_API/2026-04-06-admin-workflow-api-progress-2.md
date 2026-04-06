## 阶段日志（增量）
- **日期**：2026-04-06
- **角色**：P5_Admin_API
- **主题**：审核链路可追溯与幂等增强

## 本次新增
1. 新增审计实体与服务：
   - `ModerationAuditLog`
   - `ModerationAuditLogMapper`
   - `ModerationAuditLogService` / `ModerationAuditLogServiceImpl`
2. `AdminReviewService` 在人工操作后写入审计日志（记录 before/after 决策与风险分）。
3. `AdminModerationController` 新增幂等键机制：
   - `GET /api/admin/moderation/idempotency-key`
   - 操作接口要求 `X-Idempotency-Key`
   - 幂等键有效期 5 分钟，重复或过期返回 `409`
4. `init.sql` 新增 `moderation_audit_log` 表定义。

## 价值
- 满足“人工复核可追溯”要求。
- 降低重复点击导致的重复封禁/解除风险。
