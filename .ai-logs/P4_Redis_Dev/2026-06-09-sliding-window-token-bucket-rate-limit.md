# 2026-06-09 Redis 限流平滑化

## 背景

固定窗口虽然已完成分布式化，但在窗口边界仍可能出现瞬时双倍突刺：上一窗口尾部打满 + 下一窗口头部继续打满。为降低高并发 ingest 扩容后的边界抖动，本次将限流算法升级为 Redis 平滑限流。

## 改动

| 文件 | 说明 |
|---|---|
| `IngestionRateLimiterService` | 默认使用 Redis Lua + ZSet 滑动窗口；支持 `starshield.rate-limit.algorithm=token-bucket` 切换令牌桶 |
| `application.yml` / `application-docker.yml` | 新增 `starshield.rate-limit.algorithm`，Docker 可用 `STARSHIELD_RATE_LIMIT_ALGORITHM` 覆盖 |
| `IngestionRateLimiterServiceTest` | 覆盖滑动窗口、令牌桶、Redis 故障 fail-open/fail-closed、本地降级 |

## 结果

- 全局 / IP / player 三维仍共享 Redis key，可随 `starshield-ingest` 横向扩容。
- 默认滑动窗口精确限制最近 `window-ms` 内请求数，避免固定窗口秒边界突刺。
- Redis 不可用且允许 fallback 时，本地降级为令牌桶，不再回退固定窗口。

## 后续

- 生产可按流量模型评估是否从默认 `sliding-window` 切到 `token-bucket`。
- 如 Redis 成为瓶颈，可在网关层增加粗粒度限流，应用层保留精细维度。
