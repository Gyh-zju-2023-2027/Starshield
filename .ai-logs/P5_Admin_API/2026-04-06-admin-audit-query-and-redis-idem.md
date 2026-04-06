## 阶段日志（增量）
- **日期**：2026-04-06
- **角色**：P5_Admin_API
- **主题**：审计日志查询接口与幂等升级

## 本次新增
1. 新增接口：`GET /api/admin/moderation/{id}/audit-logs`。
2. `AdminReviewService` 新增 `queryAuditLogs`，按时间倒序返回操作历史。
3. 幂等控制从内存切换到 Redis 服务 `IdempotencyService`。
4. 文档同步更新：
   - `docs/api-change-log.md`
   - `docs/error-codes.md`

## 价值
- 后台可直接展示记录级操作时间线。
- 多实例部署下审核操作幂等性更可靠。
