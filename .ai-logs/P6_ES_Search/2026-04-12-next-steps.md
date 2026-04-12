# 任务：整理 P6 下一步执行说明

日期：2026-04-12 | 开发者：P6_ES_Search

## 1. 核心提示词 (Prompt)

"请基于 README.md、.airules、docs/api-spec.yaml 和当前归档检索实现，重新整理 P6_ES_Search 的下一步执行说明，要求明确 Elasticsearch 8.x 正式检索链路的推进顺序，突出从 MySQL / Spring Data Elasticsearch 过渡态迁移到 Elasticsearch Java Client 8.x DSL 的优先级，并记录本次提示词更新带来的新增、调整和删减内容。"

## 2. 本次修改/增减内容 (Modifications)

- 新增：按 `example.md` 风格补充“核心提示词 (Prompt)”章节，明确这份文档的生成目标。
- 新增：补充“本次修改/增减内容”章节，记录这次格式和内容整理的范围。
- 调整：将原先直接进入“目标/按顺序执行”的日志结构，重排为“任务 + Prompt + Modifications + AI 决策依据 + 下一步执行”的格式。
- 调整：保留原有 10 步执行清单，但将其归入独立的“下一步执行说明”章节，便于后续持续维护。
- 调整：明确当前最优先事项是将 `ArchiveSearchService` 从“内存过滤”切换到“ES DSL + search_after”。
- 删减：移除原文件顶部零散的元信息列表，改为与 `example.md` 一致的标题行摘要格式。
- 说明：当前仓库中未检索到 PDF 文件，因此本次未纳入 PDF 约束分析。
- 追加：已完成一轮运行态验证，并确认 `ArchiveSearchService` 可以返回只存在于 ES、不存在于 MySQL 的文档。

## 3. AI 决策依据

- 依据 `README.md`、`.airules`、`docs/api-spec.yaml` 和现有后端代码，当前项目的 ES 能力仍处于过渡态，说明文档应优先服务于迁移落地，而不是泛化描述能力边界。
- 现有 `ArchiveSearchService` 的 ES 路径仍是“分页拉取 + Java 内存过滤”，这与 P6 的 `search_after`、显式 Mapping、ES Java Client 8.x 目标不一致，因此执行说明必须把读路径替换列为最高优先级。
- `example.md` 的结构更适合作为阶段日志模板，所以保留示例中的简洁章节组织，同时继续承载本项目实际需要的分步执行清单。

## 4. 下一步执行说明

### 目标

把 StarShield 归档检索从“可用的 MySQL / Repository 过渡态”推进到“面向 Elasticsearch 8.x 的正式检索链路”。

### 按顺序执行

1. 先确认当前基线。
   - 阅读 `ChatMessageLog`、`ChatMessageIndex`、`ArchiveSearchService`、`ArchiveSyncService`。
   - 标出哪些字段已入 ES，哪些字段仍未进入索引。
   - 标出哪些查询仍在 Java 内存中过滤。

2. 产出正式 Mapping v1。
   - 以 `ChatMessageLog` 为准整理完整字段映射。
   - 显式设置 `dynamic: false`。
   - 为 `content` 配置分词器，为过滤字段使用 `keyword`，为 `aiAnalysisResult` 设置仅存储不检索。
   - 在 `.ai-logs/P6_ES_Search/` 新增 Mapping 版本记录。

3. 明确索引与别名策略。
   - 采用类似 `chat_message_archive_v1` 的物理索引名。
   - 预留读别名，例如 `chat_message_archive`。
   - 写清楚后续 Mapping 升级时如何重建索引和切别名。

4. 替换读路径实现。
   - 不再依赖 `ChatMessageIndexRepository.findAll(...).stream().filter(...)`。
   - 引入 Elasticsearch Java Client 8.x 查询 DSL。
   - 按 `keyword/playerId/decision/labels/startTime/endTime` 组装 Bool Query。
   - 保持返回结构继续对齐 `/api/archive/search`。

5. 处理分页模型。
   - 服务内部改为稳定排序字段，例如 `create_time desc, id desc`。
   - 使用 `search_after` 代替深翻页。
   - 如果前端暂时还在传 `page/limit`，先保留接口形状，但在日志中记录这是临时兼容层。

6. 增加高亮和聚合能力。
   - 为 `keyword` 检索增加 `content` 高亮片段。
   - 增加平台分布聚合。
   - 增加时间趋势聚合。
   - 给所有 aggregation 设置安全的 `size` 上限。

7. 校验写链路一致性。
   - 检查 `ArchiveSyncService` 是否完整同步 `decision/riskScore/labels/hitWords/aiAnalysisResult/createTime`。
   - 明确消费成功、ES 写失败时的补偿策略。
   - 设计 DLQ 重放或补录任务，但不要在同一轮把范围扩散过大。

8. 设计降级方案。
   - 约定 ES 不可用时是否回落 MySQL。
   - 如果允许回落，必须在代码与日志中写明它只是兜底，不是主路径。
   - 明确哪些能力在降级模式下不可用，例如高亮、聚合、语义检索。

9. 再推进语义检索。
   - 只有在关键词检索和过滤检索稳定后，再评估 `dense_vector` 和 kNN。
   - 先定义 embedding 字段来源、维度、生成时机和回填策略。
   - 不要在基础检索尚未稳定时把向量检索混入主链路。

10. 每完成一步都补日志。

- 在 `.ai-logs/P6_ES_Search/` 记录：做了什么、为什么这样做、影响了哪些接口、还有什么未完成。
- 如果修改了 Mapping，单独记录版本号和回滚方式。

### 当前最优先事项

优先级最高的是第 4 步和第 5 步：把 `ArchiveSearchService` 从“内存过滤”切到“ES DSL + search_after”。在这之前，不要把注意力放到向量检索或复杂可视化上。

### 2026-04-12 进度更新

- 已完成：`ArchiveSearchService` 迁移到 ES Java Client 8.x DSL，并在服务内部使用 `search_after` 分页。
- 已完成：针对 `ArchiveSearchService` 的运行态验证，接口已命中一条只存在于 ES 的验证文档，证明 API 已实际走到 ES 分支。
- 未完成：`ArchiveSyncService` 的端到端双写验证仍未闭环，当前上传测试消息进入了 DLQ，需要继续分离“ES 写入问题”和“消费链路问题”。
- 责任边界：DLQ 若由 ES 写入引起，继续由 P6 处理；若由消费者编排、消息 ACK/NACK、AI 审核或 Redis 规则热更新引起，则分别交给 `P2_DevOps`、`P3_AI_Engine`、`P4_Redis_Dev`。
