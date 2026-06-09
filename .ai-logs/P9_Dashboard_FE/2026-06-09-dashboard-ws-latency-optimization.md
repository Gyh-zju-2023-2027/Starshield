## 阶段日志
- **日期**：2026-06-09
- **角色**：P9_Dashboard_FE
- **主题**：大屏 WebSocket 多开首消息延迟优化

## 1. 核心提示词 (Prompt)
"多开页面测试后发现首消息延迟 P50≈4486ms / P99≈5623ms，直接优化延迟。"

## 2. 现象

用户在 Docker 网关 `ws://127.0.0.1:8080/ws/dashboard` 上运行：

```bash
node stress-test/ws_dashboard_load.js --host ws://127.0.0.1:8080 --connections 100 --slow 5 --ramp-ms 10
```

结果：

| 指标 | 数值 |
|------|------|
| 连接数 | 100 / 100 |
| 断开 / 错误 | 0 / 0 |
| 首消息延迟 | P50=4486ms，P99=5623ms |

## 3. 根因

`DashboardPushService` 原来使用 `@Scheduled(fixedDelay = 5000)` 定时广播；`DashboardWebSocketHandler.afterConnectionEstablished` 只登记 session，不推送首屏快照。新页面必须等待下一轮 5 秒广播，因此首消息延迟接近 5 秒。

## 4. 变更说明 (Modifications)

| 文件 | 变更 |
|------|------|
| `DashboardPayloadService` | 新增大屏 payload 构建服务，复用 `/api/dashboard/metrics` 结果序列化逻辑 |
| `DashboardWebSocketHandler` | 建连后立即发送一次当前快照 |
| `DashboardPushService` | 定时广播改为 `fixedDelayString=${starshield.dashboard.push-interval-ms:5000}` |
| `application.yml` | 新增 `starshield.dashboard.push-interval-ms: 5000`，保留原 5s 定期推送节奏 |
| `DashboardWebSocketHandlerTest` | 覆盖建连立即推首包 |
| `docs/business-logic.md` | 同步大屏 WebSocket 首包与广播周期说明 |
| `stress-test/ws_dashboard_benchmark.js` | 新增 1000+ WebSocket 并发基准脚本，拆分建连延迟与首包延迟 |

## 5. 预期效果

- 首消息延迟从等待 5 秒定时器，降为一次 metrics 查询 + WebSocket send 的耗时
- 多开连接稳定性逻辑不变
- 持续刷新周期默认保持 5 秒，可通过 `starshield.dashboard.push-interval-ms` 调整

## 6. 验证

### 6.1 验证命令

```bash
docker compose up -d --build starshield-api gateway
node stress-test/ws_dashboard_load.js --host ws://127.0.0.1:8080 --connections 100 --slow 5 --ramp-ms 10

# 1000+ 并发连接基准，不模拟慢客户端，适合观察服务端容量
node stress-test/ws_dashboard_benchmark.js --host ws://127.0.0.1:8080 --connections 1000 --ramp-ms 2 --duration 60
```

### 6.2 1000 连接补测结果

| 指标 | 数值 |
|------|------|
| attempted / opened / online | 1000 / 1000 / 1000 |
| first messages | 1000 / 1000 |
| closed / errors | 0 / 0 |
| messages | 12000 |
| open latency | P50=3ms，P95=71ms，P99=159ms |
| first from open | P50=4ms，P95=121ms，P99=210ms |
| first from create | P50=7ms，P95=200ms，P99=271ms |

结论：建连立即推首包后，1000 连接下首包延迟已从秒级降至毫秒级；定期广播保持 5s，稳态吞吐约 200 msg/s。
