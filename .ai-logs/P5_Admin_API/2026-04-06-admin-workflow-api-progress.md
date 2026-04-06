## 阶段日志
- **日期**：2026-04-06
- **角色**：P5_Admin_API
- **任务**：智能封禁与人工复核工作流 API

## 已完成
1. 新增审核后台接口：
   - `GET /api/admin/moderation/pending`
   - `POST /api/admin/moderation/{id}/confirm-ban`
   - `POST /api/admin/moderation/{id}/release`
2. 新增 `AdminReviewService`，实现：
   - 待复核记录查询（按风险分降序）
   - 人工确认封禁（决策置为 `BLOCK`）
   - 人工解除封禁（决策置为 `PASS`）
3. 与审核字段联动：`decision/status/riskScore/labels`。

## 变更文件
- `starshield-backend/src/main/java/com/starshield/backend/controller/AdminModerationController.java`
- `starshield-backend/src/main/java/com/starshield/backend/service/AdminReviewService.java`

## 下一步
- 增加操作审计表（operator/before/after/timestamp）与幂等令牌。
