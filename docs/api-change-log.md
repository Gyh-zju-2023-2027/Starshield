# API 变更记录（契约优先）

> 所有接口字段变更，必须先更新 `docs/api-spec.yaml`，再更新代码。

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
