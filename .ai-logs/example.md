# 任务：实现 ES 关键词高亮检索
日期：2024-10-24 | 开发者：P6

## 1. 核心提示词 (Prompt)
"帮我编写一个 Elasticsearch 聚合查询接口，要求对 'content' 字段进行脏话关键词匹配，并实现前端高亮所需的 highlight 标签包裹，同时接入 RabbitMQ 监听器同步数据。"

## 2. 变更说明 (Modifications)
- 新增：`com.starshield.service.SearchService.searchWithHighlight()`
- 修改：`elasticsearch-config.yml` 增加了映射配置。
- 优化：引入了搜索结果缓存，过期时间 5 分钟。

## 3. AI 决策依据
- 使用 RestHighLevelClient 确保兼容性。
- 采用 Pre-tags `<span>` 实现前端直接渲染。

## 4. 相关 Commit ID
`feat(es): add highlight search #abc1234`
