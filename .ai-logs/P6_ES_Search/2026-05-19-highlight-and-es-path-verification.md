# 任务：P6 高亮检索与 ES 路径验证

日期：2026-05-19 | 开发者：P6_ES_Search

## 背景

P6 基础检索已经迁移到 Elasticsearch Java Client 8.x DSL，并完成 `search_after`、聚合分析、回填与 MySQL 临时降级。但 `P6_ES_Search.prompt` 中仍要求高亮搜索能力，原 `/api/archive/search` 契约只返回 `ChatMessageLog[]`，不适合直接塞入高亮元数据。

## 索引版本

- 物理索引：`chat_message_archive_v1`
- 读写入口：`chat_message_archive`
- 本地兼容策略：若本机已经存在 `chat_message_archive` 物理索引，初始化脚本直接复用该索引，避免 ES 同名索引/别名冲突。
- Mapping 文件：
  - `starshield-backend/src/main/resources/es/chat_message_archive_v1_mapping.json`
  - `starshield-backend/src/main/resources/es/chat_message_archive_v1_mapping_standard.json`
- 动态字段：`dynamic: false`
- 稳定排序：`create_time desc, id.keyword desc`

## 接口影响

保留 `/api/archive/search` 原有契约不变，仍返回 `ChatMessageLog[]`。

新增 `/api/archive/search/highlight`，已同步更新 `docs/api-spec.yaml`。响应为 `ArchiveSearchHit[]`：

- `message`：原始 `ChatMessageLog`
- `highlights`：ES 返回的字段高亮片段
- `highlightContent`：优先取 `content` 高亮，未命中时回退原始内容

## 降级策略

当 ES 未启用、ES Client 不可用或 ES 查询异常时：

- `/api/archive/search` 显式降级到 MySQL，并记录 `[ArchiveSearch] path=MYSQL`。
- `/api/archive/search/highlight` 显式降级到 MySQL，返回空 `highlights`，`highlightContent` 使用原始 `content`，并记录 `[ArchiveSearchHighlight] path=MYSQL`。
- `/api/archive/analysis` 显式降级到 MySQL 样本聚合，并记录 `[ArchiveAnalyze] path=MYSQL`。

## 本次代码修正

- 新增 `ArchiveSearchHit` DTO。
- 新增 `GET /api/archive/search/highlight`。
- `ArchiveSearchService` 增加 ES Highlight DSL：`content`、`hit_words`、`labels`。
- 修正 ES 精确过滤、聚合与稳定排序字段，统一使用 `.keyword` 子字段，兼容本机动态创建过的索引。
- `date_histogram` 增加 `min_doc_count=1`，避免前端趋势图收到大量 0 值日期桶。
- `docs/api-spec.yaml` 新增高亮检索契约。
- `starshield-frontend/src/api/archive.js` 新增 `searchArchiveWithHighlight` API 封装。
- `aiskills/P6_ES_Search.prompt` 同步补充 `reasonTag`、高亮独立接口约束和向量检索阶段边界。

## 验证

- `POST /api/archive/reindex?batchSize=500&maxRows=1000` 返回 `synced=1000`。
- `GET http://localhost:9200/chat_message_archive/_count` 返回 `count=1000`。
- 通过仅写入 ES、不写入 MySQL 的探针文档验证后端可查到 ES-only 数据，确认不是 MySQL 兜底；验证后已删除探针。
- `GET /api/archive/search?decision=BLOCK&limit=10` 返回 10 条 BLOCK 数据。
- `GET /api/archive/analysis?decision=BLOCK&topHitLimit=5` 返回平台分布 `GAME_INNER=84, BILIBILI=83, WEIBO=83`，时间趋势 `2026-04-01=250`。
- 前端联调：
  - Vite dev server：`http://127.0.0.1:5173/`
  - 前端代理 `/api/archive/search?decision=BLOCK&limit=3` 返回 BLOCK 列表。
  - 前端代理 `/api/archive/analysis?decision=BLOCK&topHitLimit=5` 返回平台分布、趋势和 Top Hits。
  - 前端代理 `/api/archive/search/highlight?keyword=代充&decision=BLOCK&limit=2` 返回 `<mark>` 高亮片段。
  - `starshield-frontend/src/views/BanAnalytics.vue` 增加 `parseDateTime`，避免 `yyyy-MM-dd HH:mm:ss` 在不同浏览器中排序解析不稳定。
  - `BanAnalytics.vue` 搜索框接入 `/api/archive/search/highlight`：输入关键词后列表切换为 ES 高亮检索结果，封禁内容列渲染 `<mark>` 片段；空关键词时恢复原 BLOCK 列表。
  - 高亮渲染增加 HTML 转义，只允许 ES 返回的 `<mark>` 标签生效。
  - `npm run build` 通过。

## 待办

- 基础检索稳定后，再推进 `text_embedding` / kNN 语义相似违规内容召回。
- 若需要前端展示高亮片段，可在检索页面改用 `/api/archive/search/highlight`，并对 `<mark>` 内容做安全渲染控制。
