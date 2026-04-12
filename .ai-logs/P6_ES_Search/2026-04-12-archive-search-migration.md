# 任务：ArchiveSearchService 迁移到 ES Java Client

日期：2026-04-12 | 开发者：P6_ES_Search

## 1. 核心提示词 (Prompt)

"把 ArchiveSearchService 从 Spring Data Elasticsearch Repository 拉页后内存过滤的过渡实现，迁移为 Elasticsearch Java Client 8.x DSL 查询，并在保留现有 page/limit 接口形状的前提下，内部使用 search_after 稳定排序分页。"

## 2. 本次修改/增减内容 (Modifications)

- 新增：`ElasticsearchClientConfig`，显式提供 Elasticsearch Java Client 8.x 所需的 `RestClient`、`ElasticsearchTransport` 和 `ElasticsearchClient` Bean。
- 修改：`pom.xml` 增加 `co.elastic.clients:elasticsearch-java` 依赖。
- 修改：`ArchiveSearchService` 改为使用 ES DSL 组装 bool query，并以 `create_time desc, id desc` 为稳定排序字段。
- 修改：ES 读路径内部改为 `search_after` 迭代翻页，不再依赖 `findAll(PageRequest...).stream().filter(...)`。
- 修改：`ArchiveSearchService` 改为读取 ES 原始 `_source` 并手动映射为 `ChatMessageLog`，降低运行态字段绑定偏差导致的结果丢失。
- 修改：`ArchiveSearchService` 增加运行态路径日志，用于区分 `path=ES` 与 `path=MYSQL`。
- 修改：`ArchiveSyncService` 从 Spring Data Repository 双写切换为 `ElasticsearchClient.index(...)`，并将 ES 次级索引异常降级为告警，不再反向放大为主链路硬失败。
- 保留：`page/limit` 作为当前外部接口兼容层；当 ES 关闭或查询失败时继续回退 MySQL。

## 3. AI 决策依据

- 当前实现的 ES 路径本质上不是检索，而是取一批文档后在 Java 内存中过滤，无法满足 P6 对亿级检索的要求。
- 对外接口已经在 `docs/api-spec.yaml` 中定义为 `page/limit`，本轮先保持契约稳定，在服务内部切换为 `search_after`，避免同步扩大前后端改造范围。
- `create_time desc, id desc` 可提供稳定排序，适合作为 `search_after` 的基础排序组合。

## 4. 风险与后续

- 当前接口还没有暴露游标，深页访问仍需在服务内部逐页推进 `search_after`；后续若要彻底发挥 ES 优势，应把外部接口升级为 cursor 模式。
- 当前只完成了查询链路迁移，高亮、聚合、别名切换和 Mapping 版本化仍需后续继续推进。

## 5. 运行态验证结果

- 已启动本地验证依赖：MySQL、Redis、RabbitMQ、Elasticsearch 8.x。
- 已确认 `chat_message_archive` 索引存在，并手动写入一条只存在于 ES、不存在于 MySQL 的验证文档：
  - `player_id = P6_ES_ONLY_001`
  - `content = P6_DIRECT_ES_20260412 validation document`
- 已调用 `GET /api/archive/search?playerId=P6_ES_ONLY_001&limit=10`。
- 接口返回命中了上述 ES-only 文档，且 MySQL 中不存在该 `player_id`，因此可以确认：**`ArchiveSearchService` 当前运行态已经真正走到了 ES 分支，而不是 MySQL 兜底路径。**

## 6. 未完成事项

- `ArchiveSyncService` 的运行态双写还未完成端到端确认，当前仅完成了 P6 范围内的实现替换与编译校验。
- ES Mapping 仍是运行时自动形成的过渡态，尚未落地显式 Mapping、索引版本与别名切换策略。
- 搜索结果当前只验证了过滤命中，尚未补高亮、聚合和语义检索能力。
- 对外接口仍是 `page/limit` 兼容模型，尚未升级为真正的游标分页接口。

## 7. DLQ 责任边界

- 已确认上传测试消息进入了 `chat.message.dlq`，这说明失败发生在消费链路，而不是 `ArchiveSearchService` 查询链路。
- 其中属于 P6 的部分：
  - 若死信根因是 ES 次级索引写入失败、ES 索引结构不兼容、ES 客户端调用错误，则由 P6 继续处理。
- 其中超出 P6 的部分：
  - 若死信根因是 `ChatMessageConsumer` 消费编排、ACK/NACK、死信策略、消息重放机制问题，应交给消息链路 / DevOps 负责人处理，角色更接近 `P2_DevOps`。
  - 若死信根因是规则引擎、AI 审核、审核决策合并等业务处理失败，应交给 AI 审核链路负责人处理，角色更接近 `P3_AI_Engine`。
  - 若死信根因和 Redis 规则热更新、敏感词配置读取有关，应交给规则缓存负责人处理，角色更接近 `P4_Redis_Dev`。
