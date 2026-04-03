# API 契约初始化记录

- **日期**：2026-03-31
- **角色**：P5_Admin_API
- **任务**：建立接口契约文档基线

## 契约基线
- 接口：`POST /api/chat/upload`
- 请求模型：`ChatMessageUploadRequest`
- 响应模型：`ResultVoid`
- 状态字段：`status` 枚举 `0/1/2`

## 约束
- 禁止擅自变更字段名（如 `playerId`、`aiAnalysisResult`）。
- 所有变更必须先更新 `docs/api-spec.yaml`。
