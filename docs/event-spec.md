# MQ 事件契约（StarShield）

## 事件：chat.message.queue

- Exchange: `chat.direct.exchange`
- Routing Key: `chat.message.routing.key`
- Queue: `chat.message.queue`
- DLQ: `chat.message.dlq`

## 消息体 JSON Schema（逻辑）

```json
{
  "id": 123456789012345678,
  "playerId": "P1234567",
  "content": "这把操作太猛了！",
  "platform": "GAME_INNER",
  "status": 0,
  "aiAnalysisResult": null,
  "createTime": "2026-03-31T10:20:30"
}
```

## 字段说明

| 字段 | 说明 |
|---|---|
| `playerId` | 玩家 ID |
| `content` | 发言内容 |
| `platform` | 来源平台 |
| `status` | 0待处理、1正常、2违规 |
| `aiAnalysisResult` | AI 分析结果 JSON 字符串 |
| `createTime` | 记录创建时间 |

## 契约约束

1. 消息字段名必须与 `docs/api-spec.yaml` 保持一致。
2. 禁止生产端与消费端各自改字段名。
3. 若变更字段，必须先更新文档，再升级双方代码。
