## 阶段日志
- **日期**：2026-06-08
- **角色**：P2_DevOps
- **主题**：接入层 Redis 分布式限流改造

## 1. 核心提示词 (Prompt)
"继续做 Redis 分布式限流，把现在单 JVM 的固定窗口换成可随 ingest 扩容的实现，顺便更新 ai-logs。"

## 2. 背景

此前 `IngestionRateLimiterService` 使用 JVM 内存 `ConcurrentHashMap` 固定窗口计数。该实现适合单体或单个 ingest，但在 `docker compose up --scale starshield-ingest=N` 后，每个实例各自计数，会导致全局 / IP / player 限流按实例数被放大。

## 3. 变更说明 (Modifications)

| 文件 | 变更 |
|------|------|
| `IngestionRateLimiterService` | 改为 Redis Lua 原子固定窗口：`INCR` + 首次 `PEXPIRE`；按 global / ip / player 三维共享计数 |
| `IngestionRateLimiterService` | Redis key 对 identity 做 SHA-256，避免特殊字符污染 key；Redis 故障时默认 fallback 到本机窗口 |
| `application.yml` | 新增 `starshield.rate-limit.window-ms`、`redis-enabled`、`fallback-to-local`、`key-prefix` |
| `application-docker-ingest.yml` | ingest profile 不再排除 Redis 自动配置 |
| `docker-compose.yml` | `starshield-ingest` 增加 `REDIS_HOST=redis` 与 Redis health 依赖 |
| `IngestionRateLimiterServiceTest` | 覆盖 Redis 计数限流、Redis 故障本地降级、关闭降级 fail-closed、本机模式 |
| `README.md` / `DEPLOY.md` / `docs/business-logic.md` / `docs/docker-microservices.md` / `docs/test-report.md` | 同步分布式限流状态与部署说明 |

## 4. 关键配置

```yaml
starshield:
  rate-limit:
    global-qps: 20000
    ip-qps: 300
    player-qps: 30
    window-ms: 1000
    redis-enabled: true
    fallback-to-local: true
    key-prefix: starshield:rate-limit
```

## 5. 验证

```bash
cd starshield-backend
mvn -q -Dtest=IngestionRateLimiterServiceTest test
mvn test -q
```

结果：两条命令均通过。

## 6. 后续建议

- [ ] 在 Docker 微服务模式下重跑 `secure-test` 限流用例，确认多 ingest 扩容后全局计数不被放大
- [ ] 压测 `--scale starshield-ingest=2/4` 下的 429 曲线与 Redis CPU / 延迟
- [ ] 后续可从固定窗口升级为滑动窗口或令牌桶，降低秒边界抖动
