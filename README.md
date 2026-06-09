# ⬡ StarShield 星盾

> 海量游戏玩家发言舆情与违规智能监控中台

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6db33f?style=flat-square&logo=springboot)
![Vue](https://img.shields.io/badge/Vue-3.4-42b883?style=flat-square&logo=vue.js)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ed?style=flat-square&logo=docker)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-ff6600?style=flat-square&logo=rabbitmq)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479a1?style=flat-square&logo=mysql)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

---

## 项目简介

StarShield（星盾）是一套面向**高并发游戏/chat 接入**场景设计的玩家发言舆情与内容安全中台。

系统通过「**高速接收 → MQ 削峰 → 双引擎异步审核 → 落库 / 检索 → 运营大屏**」流水线，为游戏运营团队提供可观测、可扩展的内容安全基础设施。

**两种部署形态：**

| 形态 | 适用场景 | 入口 |
|------|----------|------|
| **单体 monolith** | 本地开发、快速联调 | `mvn spring-boot:run` |
| **Docker 微服务** | 降低接入延迟、独立扩缩容 | `docker compose up` |

---

## 逻辑架构

```
                         ┌─────────────────────────────────────┐
                         │  Nginx Gateway :8080（Docker 模式）   │
                         │  upload → ingest  |  其余 → api      │
                         └──────────┬──────────────┬────────────┘
                                    │              │
                    ┌───────────────▼──┐    ┌──────▼──────────────┐
                    │  ingest 接入服务   │    │  api 运营服务        │
                    │  限流 + MQ 投递    │    │  管理/检索/大屏 WS   │
                    └───────────────┬──┘    └──────┬──────────────┘
                                    │              │
                                    ▼              ▼
                         ┌──────────────────────────────┐
                         │  RabbitMQ  chat.message.queue  │
                         └──────────────┬───────────────┘
                                        ▼
                         ┌──────────────────────────────┐
                         │  worker 消费服务               │
                         │  引擎A(Redis) + 引擎B(AI) → DB │
                         └──────────────────────────────┘
```

审核流水线详见 [docs/business-logic.md](./docs/business-logic.md)，契约见 [docs/api-spec.yaml](./docs/api-spec.yaml)。

---

## 技术栈

| 层次 | 技术选型 | 说明 |
|------|----------|------|
| 接入层 | Spring Boot 3.2 + Tomcat | 400 线程池；ingest 模式可独立部署 |
| 消息队列 | RabbitMQ 3.x | Direct Exchange + 死信队列，手动 ACK |
| 持久层 | MySQL 8.0 + MyBatis-Plus | 雪花 ID；审计日志、战报缓存 |
| 缓存 | Redis | 分布式接入限流、敏感词 / Prompt 热更新、幂等键 |
| 检索 | Elasticsearch（可选） | ES 优先、MySQL 兜底 |
| AI | ai-service + DeepSeek | 轻量模型预筛 + LLM 复核 |
| 前端 | Vue 3 + Vite + Element Plus | 大屏、审核台、控制面、战报 |
| 部署 | Docker Compose + Nginx | ingest / worker / api 三进程 |
| 测试 | Locust + httpx | 压测与安全自动化 |

---

## 项目结构

```
StarShield/
├── starshield-backend/          # Spring Boot 后端（支持 monolith / ingest / worker / api）
│   ├── Dockerfile
│   ├── src/main/java/.../
│   │   ├── controller/          # 接入、管理、检索、大屏
│   │   ├── consumer/            # MQ 消费者（双引擎审核）
│   │   ├── config/runtime/      # @EnabledOnMode 运行时模式
│   │   └── service/             # 规则引擎、AI、归档、限流等
│   └── src/main/resources/
│       ├── application.yml
│       ├── application-docker*.yml
│       └── init.sql
│
├── starshield-frontend/         # Vue 3 运营前端
│   └── src/views/
│       ├── DashboardBoard.vue   # 实时大屏
│       ├── AdminReview.vue      # 人工复核
│       ├── ControlPanel.vue     # 敏感词 / Prompt
│       └── DailyReport.vue      # AI 治理战报
│
├── ai-service/                  # Flask 轻量打分服务
├── bilichat-ingest/             # B 站评论抓取 → 后端推送（可选）
├── stress-test/                 # Locust 压测 + WebSocket 多开
├── secure-test/                 # 安全自动化测试（20 项用例）
├── docker/                      # Nginx 网关配置
├── docker-compose.yml           # 一键微服务栈
│
├── docs/                        # 契约、架构、测试报告
│   ├── api-spec.yaml
│   ├── architecture.md
│   ├── docker-microservices.md
│   └── test-report.md           # 压测 + 安全测试报告
│
├── DEPLOY.md                    # 本地部署详细说明
└── .ai-logs/                    # 各角色协作日志
```

---

## 快速启动

### 方式 A：本地单体（开发推荐）

详细步骤见 [DEPLOY.md](./DEPLOY.md)。

```bash
# 1. 初始化数据库
mysql -u root -p < starshield-backend/src/main/resources/init.sql

# 2. 编辑 application.yml 中的 MySQL 密码，启动中间件（MySQL / Redis / RabbitMQ）

# 3. 启动后端
cd starshield-backend && mvn spring-boot:run

# 4. 启动前端
cd starshield-frontend && npm install
VITE_ADMIN_API_KEY=你的管理令牌 npm run dev
# 浏览器 http://localhost:5173
```

> 只看公开大屏/检索时可不配置 `VITE_ADMIN_API_KEY`；执行审核、控制面写操作、爬取任务等管理功能时需要与后端 `starshield.security.admin-api-key` 一致。

### 方式 B：Docker 微服务（低延迟 / 可扩容）

详见 [docs/docker-microservices.md](./docs/docker-microservices.md)。

```bash
# 首次或代码变更后启动全栈
docker compose up -d --build

# 对外入口仍为 http://localhost:8080
curl -s http://localhost:8080/api/dashboard/metrics

# 扩容 worker / ingest（接入限流走 Redis，扩容后仍共享计数）
docker compose up -d --scale starshield-worker=2 --scale starshield-ingest=2

# 只重建运营 API / 大屏网关，适合后端管理端或 WebSocket 改动后快速验证
docker compose up -d --build starshield-api gateway
```

**Docker 栈组件**

| 服务 | 端口 / 入口 | 说明 |
|------|-------------|------|
| `gateway` | `localhost:8080` | Nginx 统一入口；`/api/chat/upload` 转发到 ingest，其余 API / WS 转发到 api |
| `starshield-ingest` | 容器内 `8080` | 高并发接入；Redis 分布式限流 + RabbitMQ 投递 |
| `starshield-worker` | 容器内 `8080` | MQ 消费、规则/AI 审核、MySQL 落库、可选 ES 同步 |
| `starshield-api` | 容器内 `8080` | 管理后台、检索、大屏指标与 WebSocket |
| `mysql` | 宿主机 `3307` → 容器 `3306` | Docker 数据库，避免和本机 MySQL 3306 冲突 |
| `redis` | `6379` | 分布式限流、敏感词/Prompt、幂等键 |
| `rabbitmq` | `5672` / `15672` | 消息队列与管理控制台 |
| `ai-service` | `5050` | 轻量模型 `/score` |

**常用 Docker 命令**

```bash
docker compose ps                         # 查看容器状态
docker compose logs -f gateway starshield-api
docker compose logs -f starshield-ingest starshield-worker
docker compose down                       # 停止但保留 MySQL 数据卷
docker compose down -v                    # 停止并清空数据卷
```

> Docker MySQL 使用宿主机 `3307`，本机 `mvn spring-boot:run` 默认连 `3306`。如果你看到数据量不一致，先确认自己连的是 Docker 库还是本机库。

**运行时模式**（同一 JAR，环境变量切换）：

| 模式 | 职责 | Profile |
|------|------|---------|
| `monolith` | 全功能（默认） | — |
| `ingest` | 仅 `POST /api/chat/upload` | `docker,docker-ingest` |
| `worker` | MQ 消费 + 审核落库 | `docker,docker-worker` |
| `api` | 管理 / 检索 / WebSocket | `docker,docker-api` |

---

## 测试与质量

| 类型 | 目录 | 说明 |
|------|------|------|
| 压力测试 | `stress-test/` | Locust 阶梯加压 + WS 长连接脚本 |
| 安全测试 | `secure-test/` | 限流 / 注入 / 鉴权 / 幂等自动化 |
| 测试报告 | [docs/test-report.md](./docs/test-report.md) | 压测 + 安全测试合并报告 |

```bash
# 后端单元测试（默认不连接 Redis / RabbitMQ / MySQL / DeepSeek）
cd starshield-backend && mvn test

# 真实集成测试需显式开启，并先启动依赖
STARSHIELD_RUN_INTEGRATION_TESTS=true mvn test

# 压测
cd stress-test
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/locust -f locustfile_ingest.py --host=http://localhost:8080

# 安全测试
cd secure-test
.venv/bin/python run_security_tests.py --host http://localhost:8080

# 1000+ 大屏 WebSocket 并发基准（只读，不写数据库）
cd ../stress-test
node ws_dashboard_benchmark.js --host ws://127.0.0.1:8080 --connections 1000 --ramp-ms 2 --duration 60
```

---

## 核心设计亮点

### 1. 高并发削峰填谷

`ChatMessageController` 仅做 **序列化 → 投递 MQ → 返回 200**，不触碰 DB / AI。Docker ingest 模式进一步剥离 MyBatis 与消费者，专用于接入。

### 2. 双引擎审核

- **引擎 A**：Redis 敏感词 + 布隆过滤器，`RuleEngineService.fastCheck`
- **引擎 B**：轻量 HTTP 打分 + DeepSeek LLM，`AiAnalysisService.analyze`
- 合并决策后落 MySQL，可选双写 ES

### 3. 可靠消息与幂等

- RabbitMQ 手动 ACK / NACK + 死信队列
- 管理端操作 Redis 幂等键（`X-Idempotency-Key`）

### 4. 规则热更新

敏感词与 Prompt 存 Redis，控制面 PUT 即时生效，Worker 下次审核自动读取。

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [DEPLOY.md](./DEPLOY.md) | 本地部署、B 站评论导入、ES 启用 |
| [docs/architecture.md](./docs/architecture.md) | 架构与包结构 |
| [docs/business-logic.md](./docs/business-logic.md) | 审核流水线 |
| [docs/api-spec.yaml](./docs/api-spec.yaml) | OpenAPI 契约 |
| [docs/docker-microservices.md](./docs/docker-microservices.md) | Docker 微服务部署 |
| [docs/test-report.md](./docs/test-report.md) | 压测与安全测试报告 |
| [.ai-logs/](./.ai-logs/) | 各角色阶段协作日志 |

---

## 后续规划

- [x] 接入 DeepSeek / 轻量模型双引擎审核
- [x] Elasticsearch 检索与中台 API
- [x] Docker 微服务拆分（ingest / worker / api）
- [x] 压测与安全自动化脚本
- [x] 管理 / 控制面 / reindex API Key 鉴权
- [x] 接入层 `@Valid` 必填字段校验
- [x] 规则引擎 BloomFilter 漏审修复
- [x] `Result` 错误码同步 HTTP 状态码
- [x] Redis 分布式限流（替换单机内存固定窗口）
- [ ] Redis 限流升级为滑动窗口 / 令牌桶，降低固定窗口边界抖动
- [ ] Prometheus + Grafana 监控 QPS / MQ 深度 / 消费延迟
- [ ] Kubernetes Helm Chart
- [ ] 前端按路由拆包，降低生产构建 chunk 体积

---

## License

MIT © 2026 StarShield
