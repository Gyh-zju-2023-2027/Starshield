# API 契约初始化记录

- **日期**：2026-03-31
- **角色**：P5_Admin_API
- **任务**：建立接口契约文档基线

## 1. 核心提示词 (Prompt)
"请建立 `POST /api/chat/upload` 的接口契约基线（请求/响应模型与状态枚举），并约定所有字段变更必须先更新 `docs/api-spec.yaml`，禁止擅自改名。"

## 契约基线
- 接口：`POST /api/chat/upload`
- 请求模型：`ChatMessageUploadRequest`
- 响应模型：`ResultVoid`
- 状态字段：`status` 枚举 `0/1/2`

## 约束
- 禁止擅自变更字段名（如 `playerId`、`aiAnalysisResult`）。
- 所有变更必须先更新 `docs/api-spec.yaml`。
