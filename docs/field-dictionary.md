# 字段字典（StarShield）

## chat_message_log / ChatMessageUploadRequest

| 字段名 | 类型 | 必填 | 说明 | 取值/约束 |
|---|---|---|---|---|
| `id` | Long | 否 | 主键 ID（雪花算法） | 后端生成 |
| `playerId` | String | 是 | 玩家 ID | 最大长度 64 |
| `content` | String | 是 | 玩家发言内容 | 最大长度 10000 |
| `platform` | String | 是 | 来源平台 | `GAME_INNER/BILIBILI/WEIBO/DOUYIN/OTHER` |
| `status` | Integer | 否（建议传） | 审核状态 | `0=待处理,1=正常,2=违规` |
| `decision` | String | 否 | 审核决策 | `PASS/REVIEW/BLOCK` |
| `riskScore` | Integer | 否 | 风险分 | `0~100` |
| `labels` | String | 否 | 违规标签（逗号分隔） | 如 `insult,traffic_diversion` |
| `hitWords` | String | 否 | 命中敏感词（逗号分隔） | 引擎A产出 |
| `aiAnalysisResult` | String | 否 | AI 分析 JSON 串 | 上传时可为空 |
| `createTime` | Datetime | 否 | 创建时间 | 通常后端填充 |

## archive.search 查询参数

| 参数名 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `keyword` | String | 否 | 关键词（内容模糊匹配） |
| `playerId` | String | 否 | 玩家ID精确匹配 |
| `decision` | String | 否 | `PASS/REVIEW/BLOCK` |
| `labels` | String | 否 | 标签模糊匹配 |
| `startTime` | Datetime | 否 | 开始时间 |
| `endTime` | Datetime | 否 | 结束时间 |
| `page` | Integer | 否 | 页码，默认 1 |
| `limit` | Integer | 否 | 每页条数，默认 200，最大 1000 |

## 统一返回结构 `Result<T>`

| 字段名 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `code` | Integer | 是 | 状态码，`200` 成功 |
| `message` | String | 是 | 响应消息 |
| `data` | T | 否 | 业务数据 |
