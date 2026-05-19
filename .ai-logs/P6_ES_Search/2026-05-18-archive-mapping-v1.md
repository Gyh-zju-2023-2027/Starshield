# 任务：chat_message_archive_v1 显式 Mapping 基线

日期：2026-05-18 | 开发者：P6_ES_Search

## 背景

`ArchiveSearchService` 已迁移到 Elasticsearch Java Client 8.x DSL，但索引结构仍缺少显式 Mapping 与别名版本策略，不能满足 P6 对 `dynamic: false`、字段全集和索引版本记录的要求。

## 索引版本

- 物理索引：`chat_message_archive_v1`
- 读写别名：`chat_message_archive`
- Mapping 文件：`starshield-backend/src/main/resources/es/chat_message_archive_v1_mapping.json`
- 动态字段：`dynamic: false`
- 时间字段：`create_time`
- 稳定排序：`create_time desc, id desc`

## 字段基线

本版本以 `ChatMessageLog` 与 `docs/api-spec.yaml` 的归档返回契约为基线，显式覆盖：

- `id`
- `player_id`
- `content`
- `platform`
- `status`
- `decision`
- `risk_score`
- `labels`
- `hit_words`
- `ai_analysis_result`
- `reason_tag`
- `create_time`

## 接口影响

本次不修改 `/api/archive/search` 对外契约，继续保持 `page/limit` 参数和 `ChatMessageLog` 列表返回结构。服务内部继续使用 `search_after` 兼容现有分页形状。

新增 `/api/archive/analysis`，已同步更新 `docs/api-spec.yaml`。该接口返回：

- `platformDistribution`：平台 Terms 聚合，ES 桶上限为 20。
- `timeTrend`：按天 Date Histogram 趋势。
- `topHits`：按 `risk_score desc, create_time desc, id desc` 返回高风险 Top Hits，接口上限为 20。

## 降级策略

当 `starshield.archive.es-enabled=false`、ES Client 未注入或 ES 查询异常时，查询继续显式降级到 MySQL 路径，并输出 `path=MYSQL` 日志。ES 写入失败仍按次级索引失败处理，不反向影响主消息链路。

`/api/archive/analysis` 在 ES 不可用时会使用 MySQL 临时降级路径，从最多 1000 条匹配样本中计算平台分布、时间趋势和 Top Hits。该路径仅用于本地/故障兜底，不作为亿级检索最终方案。

## 本次代码修正

- `ArchiveSearchService` 的 search_after 排序字段从 `id.keyword` 修正为显式 Mapping 中存在的 `id`。
- `ArchiveSyncService` 补齐 `status`、`hit_words`、`ai_analysis_result`、`reason_tag` 写入字段。
- `ArchiveSearchService` 补齐上述字段的 ES `_source` 到 `ChatMessageLog` 映射。
- `ChatMessageIndex` 补齐字段注解，使过渡模型与显式 Mapping 保持一致。
- 新增 `scripts/init-es-archive-index.sh`，用于创建 `chat_message_archive_v1` 并绑定 `chat_message_archive` 别名。
- `DEPLOY.md` 增加 P6 Elasticsearch 归档检索启用步骤。
- 前端 `BanAnalytics.vue` 接入 `/api/archive/analysis`，页面可直接验证 ES Terms / Date Histogram / Top Hits 聚合结果。
- 修复 `scripts/init-es-archive-index.sh` 在 ES 8.12 `_aliases` API 中使用不支持字段 `ignore_unavailable` 导致的 400 问题。
- 新增 `ArchiveBackfillService` 与 `POST /api/archive/reindex`，用于将 MySQL 中已有归档数据分页回填到 ES，解决 `seed_chat_message_1000.sql` 仅写 MySQL、不自动进入 ES 的验证断点。

## 待办

- 将部署脚本补齐为创建 `chat_message_archive_v1` 并绑定 `chat_message_archive` 别名。
- 增加关键字高亮返回结构，需要先扩展 `docs/api-spec.yaml`。
- 在基础检索稳定后推进 `text_embedding` / kNN 语义召回。

## 验证

- `jq empty starshield-backend/src/main/resources/es/chat_message_archive_v1_mapping.json` 通过。
- `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn clean -DskipTests compile` 通过。
- `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn test` 已执行；除 `DashboardMetricsSmokeTest` 因本地 MySQL `localhost:3306` 连接拒绝失败外，其余可运行测试通过，`AiAnalysisServiceIntegrationTest` 按既有条件跳过。
- `bash -n scripts/init-es-archive-index.sh` 通过。
- `ruby -e 'require "yaml"; YAML.load_file("docs/api-spec.yaml")'` 通过。
- `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 mvn -DskipTests compile` 通过，当前编译 38 个后端源文件。
- `npm run build` 通过；本地首次缺少 `node_modules`，已按 `package-lock.json` 执行 `npm install` 后完成构建。

## 前端验证入口

进入前端左侧 `封禁分析` 页面：

- 列表数据调用 `/api/archive/search?decision=BLOCK`。
- 平台分布、趋势、Top Hits 调用 `/api/archive/analysis?decision=BLOCK`。
- 后端日志出现 `path=ES` 表示正在验证 ES 路径；出现 `path=MYSQL` 表示仍处于临时降级。
- 如果通过 `seed_chat_message_1000.sql` 直接导入 MySQL 测试数据，需先调用 `POST /api/archive/reindex?batchSize=500&maxRows=1000` 回填 ES，再验证 `/api/archive/search` 和 `/api/archive/analysis`。
