# API 变更记录（契约优先）

> 所有接口字段变更，必须先更新 `docs/api-spec.yaml`，再更新代码。

---

## 2026-04-06

### 接入与审核链路增强
- `POST /api/chat/upload` 新增应用内三层限流能力（全局/IP/玩家），超限返回 `429`
- 消费链路新增审核字段：`decision/riskScore/labels/hitWords`
- 新增动态规则与 Prompt 管理接口：
  - `GET /api/control/rules/sensitive-words`
  - `PUT /api/control/rules/sensitive-words`
  - `GET /api/control/prompt`
  - `PUT /api/control/prompt`
- 新增后台审核接口：
  - `GET /api/admin/moderation/idempotency-key`
  - `GET /api/admin/moderation/pending`
  - `GET /api/admin/moderation/{id}/audit-logs`
  - `POST /api/admin/moderation/{id}/confirm-ban`
  - `POST /api/admin/moderation/{id}/release`
- 新增检索接口：
  - `GET /api/archive/search`（支持 `page/limit`）

### 兼容性
- 保持 `POST /api/chat/upload` 原有核心字段不变，新增字段均为可选扩展。

---

## 2026-03-31

### 初始化
- 新增 `POST /api/chat/upload` 接口契约
- 统一返回结构：`ResultVoid`
- 请求体模型：`ChatMessageUploadRequest`
- 状态字段约定：`status = 0/1/2`

### 审核结论
- 当前后端代码与契约字段一致（`playerId/content/platform/status`）
- 无破坏性变更
