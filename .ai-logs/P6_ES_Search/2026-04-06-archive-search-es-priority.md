## 阶段日志（增量）
- **日期**：2026-04-06
- **角色**：P6_ES_Search
- **主题**：检索链路 ES 优先化 + 分页能力

## 1. 核心提示词 (Prompt)
"请实现 ArchiveSearchService：在 `es-enabled=true` 时优先查 ES、否则兜底 MySQL；控制器增加 page/limit 分页并更新 api-change-log 与 field-dictionary。"

## 本次新增
1. 新增 `ArchiveSearchService`，实现“ES 优先、MySQL 兜底”策略：
   - `starshield.archive.es-enabled=true` 时优先走 ES
   - 关闭时自动走 MySQL
2. `ArchiveSearchController` 改为调用服务层，接口新增分页参数：
   - `page`（默认 1）
   - `limit`（默认 200，最大 1000）
3. 文档更新：
   - `docs/api-change-log.md`
   - `docs/field-dictionary.md`

## 说明
- 当前 ES 路径为快速可用实现（仓储拉取 + 过滤），下一步将替换为原生 DSL 查询以进一步提升大数据量检索效率。
