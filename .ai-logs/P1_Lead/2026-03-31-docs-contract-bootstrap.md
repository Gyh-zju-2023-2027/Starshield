# 契约文档补充记录

- **日期**：2026-03-31
- **角色**：P1_Lead
- **任务**：补齐接口契约与协作文档体系

## 1. 核心提示词 (Prompt)
"请为项目补齐 OpenAPI 契约与协作文档体系（`api-spec.yaml`、接口变更日志、字段字典、错误码、事件规范），并固化 `POST /api/chat/upload` 契约；后续字段变更必须先更新文档再改代码。"

## 本次新增
1. `docs/api-spec.yaml`
2. `docs/api-change-log.md`
3. `docs/field-dictionary.md`
4. `docs/error-codes.md`
5. `docs/event-spec.md`

## 结果
- `.airules` 中“必须参考 `docs/api-spec.yaml`”约束已具备落地文件。
- 当前 `POST /api/chat/upload` 契约已固化，后续字段修改需先改文档再改代码。
