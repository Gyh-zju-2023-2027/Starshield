# 2026-06-09 Prometheus + Grafana 可观测性

## 背景

1000+ WebSocket 长连接已能稳定建立，但高并发接入链路还缺少统一监控。需要能看到接入 QPS、MQ 堆积、消费延迟和 worker 处理耗时，避免只能靠日志或 RabbitMQ 管理页人工判断。

## 改动

| 文件 | 说明 |
|---|---|
| `pom.xml` | 增加 `micrometer-registry-prometheus` |
| `application.yml` / `application-docker.yml` | 暴露 `health,info,metrics,prometheus`，增加统一 `application` tag |
| `StarshieldMetrics` | 记录接入 outcome、限流、MQ 投递、消费成功/失败、消费延迟、处理耗时 |
| `RabbitMqMetricsConfig` | 使用 RabbitAdmin 暴露主队列 / DLQ ready 数和 consumer 数 |
| `docker-compose.yml` | 新增 Prometheus `:9090` 与 Grafana `:3000` |
| `docker/prometheus/prometheus.yml` | 抓取 ingest/api/worker 的 `/actuator/prometheus` |
| `docker/grafana/**` | 自动配置 Prometheus 数据源和 `StarShield Observability` 看板 |

## 核心指标

- `starshield_ingest_requests_total`
- `starshield_ingest_rate_limited_total`
- `starshield_mq_published_total`
- `starshield_mq_queue_ready`
- `starshield_mq_consumer_lag_seconds`
- `starshield_mq_consumer_processing_seconds`

## 使用

```bash
docker compose up -d --build
```

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000`，默认 `admin/starshield`
