# 错误码规范（StarShield）

## 通用错误码

| code | 含义 | 建议处理 |
|---|---|---|
| 200 | 成功 | 正常流程 |
| 400 | 请求参数错误 | 前端提示并修正输入 |
| 401 | 未认证 | 重新登录 |
| 403 | 无权限 | 提示无权限访问 |
| 429 | 请求过于频繁 | 稍后重试 |
| 500 | 服务端异常 | 记录日志并重试 |

## 当前已落地接口

### POST `/api/chat/upload`

- 成功：`code = 200`, `message = "接收成功"`
- 失败：
  - `code = 429`, `message = "全局限流触发，请稍后重试"`
  - `code = 429`, `message = "IP 请求过于频繁，请稍后重试"`
  - `code = 429`, `message = "玩家请求过于频繁，请稍后重试"`
  - `code = 500`, `message = "消息格式异常，请检查请求参数"`
  - `code = 500`, `message = "消息接收失败，请稍后重试"`

### POST `/api/admin/moderation/{id}/confirm-ban`
- 失败：`code = 409`, `message = "幂等键无效或重复"`

### POST `/api/admin/moderation/{id}/release`
- 失败：`code = 409`, `message = "幂等键无效或重复"`

### GET `/api/admin/moderation/{id}/audit-logs`
- 成功：`code = 200`
