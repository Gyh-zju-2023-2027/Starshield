# P9 Dashboard FE Contract Update (2026-04-13)

## 1) REST Metrics Contract (`GET /api/dashboard/metrics`)

Response shape:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 1280000,
    "blocked": 40960,
    "review": 1250,
    "blockRate": 3.2,
    "latest": [
      {
        "id": 1,
        "playerId": "P10001",
        "content": "message content",
        "decision": "BLOCK",
        "createTime": "2026-04-13 18:00:00"
      }
    ]
  }
}
```

Notes:
- `blockRate` unit is `%` (already multiplied by 100 on backend).
- `latest` keeps up to 100 rows from newest to oldest.

## 2) WebSocket Contract (`/ws/dashboard`)

Server push shape (aligned to P9):

```json
{
  "type": "REALTIME_STATS",
  "timestamp": 1776074400000,
  "data": {
    "total": 1280000,
    "blocked": 40960,
    "review": 1250,
    "blockRate": 3.2,
    "latest": []
  }
}
```

Heartbeat:
- Client sends text `"ping"` every 30s.
- Server replies text `"pong"`.

Reconnect:
- Exponential backoff from 1s, max 30s delay.
- Retry cap: 5 attempts.

## 3) Frontend Refresh Rhythm

- Realtime metrics pull: every `<= 1s` (requestAnimationFrame driven loop).
- Trend chart point sampling: every `<= 5s`.
- ECharts instance cleanup: `dispose()` on component unmount.
