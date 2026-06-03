## 阶段日志
- **日期**：2026-06-02
- **角色**：P7_Performance
- **任务**：Locust + WebSocket 压测基线执行与报告输出

## 1. 核心提示词 (Prompt)
"帮我看一下 stress-test 怎么运行并跑一轮压测，生成压测报告 md 文件。"

## 2. 变更说明 (Modifications)
- 执行 `stress-test/` 下两套脚本并记录结果
- 新增合并报告：`docs/test-report.md`（压测 + 安全测试）
- 脚本目录：
  - `locustfile_ingest.py` — StairShape 阶梯加压（50→500 VU）
  - `ws_dashboard_load.js` — WebSocket 长连接 + 慢客户端

## 3. 压测结果摘要（本地 macOS 开发环境）

| 场景 | 关键指标 |
|------|----------|
| Locust 阶梯加压 | 532,393 请求，0 失败，~4,449 req/s，P99 29 ms |
| WebSocket 100 连接 | 0 错误，20 s 内 387 条广播，首消息 P50 ~2 s |

## 4. 观测与结论
- 接入层（Controller → MQ）在 500 VU 下 P99 稳定，符合设计目标
- Locust 压测机 CPU > 90%，单机发压可能限制测得的上限 QPS
- 未执行：SpikeShape 峰值冲击、Locust + WS 联合压测

## 5. 下一步
- [ ] Docker 微服务模式下复跑压测，对比 monolith P99
- [ ] 分布式 Locust 消除发压端 CPU 瓶颈
- [ ] 压测期间同步记录 RabbitMQ Ready / DLQ 深度

## 6. 相关 Commit
`2b96422` feat: Docker 微服务拆分、安全测试与压测报告
