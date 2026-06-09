# StarShield 本地部署说明

面向 macOS / Linux 本地开发。

| 形态 | 适用场景 | 入口 |
|------|----------|------|
| **单体 monolith** | 本地开发、爬取控制台、快速联调 | 本文 §4–§6 |
| **Docker 微服务** | 接入压测、独立扩缩容 worker/ingest | 本文 §9；详见 [docs/docker-microservices.md](./docs/docker-microservices.md) |

---

## 1. 环境要求

| 依赖 | 说明 |
|------|------|
| JDK 17 | `java -version` |
| Maven 3.8+ | `mvn -version`（可用 Toolchains 固定 JDK 17，见 `.mvn/toolchains.xml`） |
| Node.js 18+ / npm | `node -v` |
| Python 3.10+（可选） | `ai-service`、`bilichat-ingest`、爬取控制台需要 |
| MySQL 8 | 单体默认 **3306**；Docker 映射 **3307** |
| RabbitMQ 3.x | 消息入队与消费 |
| Redis | **接入限流**、规则控制台（敏感词 / Prompt）与引擎 A 必需 |
| Docker（可选） | `docker compose` 微服务栈 |

---

## 2. 密钥与 `.env`（推荐）

在**仓库根目录**创建 `.env`（已在 `.gitignore` 中，勿提交）：

```bash
# DeepSeek（战报 AI 总结、引擎 B 复核）
DEEPSEEK_API_KEY=sk-xxxxxxxx

# B 站爬取默认 Cookie（可选；前端控制台填写会覆盖）
BILIBILI_COOKIE=SESSDATA=xxx; bili_jct=xxx; ...

# Docker MySQL 密码（可选，默认 starshield）
MYSQL_PASSWORD=starshield
```

| 变量 | 用途 |
|------|------|
| `DEEPSEEK_API_KEY` | 单体读取环境变量 / `.env` 回退；Docker 注入 `starshield-api`、`starshield-worker` |
| `BILIBILI_COOKIE` | CLI 爬取 `--cookie` 的替代；Docker API 容器内 Python 任务的默认 Cookie |
| `MYSQL_PASSWORD` | `docker compose` 中 MySQL root 密码 |

---

## 3. 安装并启动中间件

**macOS（Homebrew）示例：**

```bash
brew install mysql@8.0 rabbitmq redis
brew services start mysql@8.0
brew services start rabbitmq
brew services start redis

rabbitmq-plugins enable rabbitmq_management   # 可选：http://localhost:15672 guest/guest
```

**Linux：** 用发行版包管理器安装并 `systemctl start` 即可。

---

## 4. 数据库（单体 / 本机 MySQL 3306）

**首次建库建表**（仓库根目录）：

```bash
mysql -u root -p < starshield-backend/src/main/resources/init.sql
mysql -u root -p starshield < starshield-backend/src/main/resources/migrate_daily_report.sql
```

**可选：导入测试数据**（约 1000 条合成数据，非 B 站真实评论）：

```bash
mysql -u root -p starshield < starshield-backend/src/main/resources/seed_chat_message_1000.sql
```

若表结构偏旧、缺 `decision` 等列，可删库后重跑 `init.sql`，或执行 `migrate_chat_message_log.sql`。

**清理压测脏数据**（Locust / 安全测试写入后，只保留真实 B 站评论 `BILI_*`）：

```bash
mysql -u root -p starshield < scripts/cleanup-stress-test-data.sql
```

---

## 5. 后端配置与启动（单体）

1. 编辑 `starshield-backend/src/main/resources/application.yml`：
   - **`spring.datasource.password`** → 本机 MySQL 密码
   - RabbitMQ / Redis 非默认端口时同步修改
   - 默认 `starshield.ai.provider=deepseek`，需配置 `DEEPSEEK_API_KEY`（见 §2）
   - 若只用轻量模型：`starshield.ai.provider=lightweight` + `starshield.ai.lightweight-url`
2. 启动：

```bash
cd starshield-backend
mvn spring-boot:run
```

控制台监听 **8080** 即成功。

**快速自检：**

```bash
curl -s http://localhost:8080/api/dashboard/metrics
curl -s http://localhost:8080/api/control/rules/sensitive-words
curl -s http://localhost:8080/api/control/prompt
# 期望 code=200；规则控制台应返回默认敏感词与 V2 Prompt
```

> **Redis 说明**：后端使用 `spring.data.redis.*`（Spring Boot 3）。首次启动会自动向 Redis 写入默认敏感词库与系统 Prompt；接入层也会用 Redis 做全局 / IP / player 分布式限流，需保证 Redis 已启动。

---

## 6. 前端

```bash
cd starshield-frontend
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**。`/api` 与 `/ws` 代理到 **8080**，请始终通过 Vite 地址访问。

| 页面 | 说明 |
|------|------|
| 数据大屏 | 实时指标与最新发言 |
| 人工复核 | 待审队列 |
| 规则控制台 | 敏感词 / Prompt 热替换（读写 Redis） |
| 爬取控制台 | B 站评论 / 直播 / 微博数据集（**需单体后端**，见 §8） |
| AI 治理战报 | 按日统计 + DeepSeek 总结 |

---

## 7. 推荐启动顺序（单体）

1. MySQL → 2. Redis → 3. RabbitMQ → 4. 后端 → 5. 前端  

（Elasticsearch 默认关闭，归档检索走 MySQL；启用见附录 ES 章节。）

---

## 8. B 站评论爬取

### 8.1 方式 A：前端爬取控制台（推荐）

**前提**：使用**单体** `mvn spring-boot:run`（后端需能执行宿主机 `bilichat-ingest/` 下的 Python）。

1. 登录 [bilibili.com](https://www.bilibili.com)，F12 → Application → Cookies
2. 复制 Cookie（**至少含 `SESSDATA`**）
3. 前端 → **爬取控制台** → 粘贴 Cookie → 填写 BV 号 → 提交

> 未填 Cookie 时，访客模式每个视频通常只能抓到少量「精选评论」。Cookie 仅存于浏览器 `sessionStorage`，**不落库**。

### 8.2 方式 B：命令行 `bilichat-ingest`

```bash
cd bilichat-ingest
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

.venv/bin/python ingest_comments.py \
  --bvid-file bvids.txt \
  --target-count 12000 \
  --cookie "$BILIBILI_COOKIE" \
  --rps 50 --workers 16 \
  -v --log-file run.log
```

也可用环境变量：`export BILIBILI_COOKIE='SESSDATA=...'`（与 `--cookie` 等价）。

### 8.3 观察链路

```bash
cd bilichat-ingest
.venv/bin/python verify_pipeline.py --watch
```

---

## 9. Docker 微服务部署

```bash
# 仓库根目录；确保 .env 已配置 DEEPSEEK_API_KEY 等
docker compose up -d --build
docker compose ps
```

| 项 | 说明 |
|----|------|
| 对外入口 | http://localhost:8080（Nginx 网关） |
| MySQL | 宿主机 **3307** → 容器 3306，密码默认 `starshield` |
| 前端 | 仍用 `npm run dev`，代理到 8080 |
| DeepSeek | `.env` 中 `DEEPSEEK_API_KEY` 自动注入 api/worker |
| 规则控制台 | 启动时自动初始化 Redis 默认词库与 Prompt |

**自检：**

```bash
curl -s http://localhost:8080/api/dashboard/metrics | head -c 200
curl -s http://localhost:8080/api/control/rules/sensitive-words
```

### 9.1 本机 MySQL → Docker MySQL 数据迁移

本机 Homebrew MySQL（3306）与 Docker MySQL（3307）并存。迁移真实数据：

```bash
# 1. Docker 侧补 daily_report_cache 表
mysql -u root -pstarshield -h 127.0.0.1 -P 3307 starshield \
  < starshield-backend/src/main/resources/migrate_daily_report.sql

# 2. 一键迁移（会提示本机 MySQL 密码）
MYSQL_LOCAL_PASSWORD='你的本机密码' ./scripts/migrate-local-to-docker.sh
```

脚本只导出本机**实际存在的表**（常见：`chat_message_log`、`moderation_audit_log`、`daily_report_cache`；**不含** `crawl_task` 若本机无此表）。

迁移后在 Docker 库清理压测数据：

```bash
mysql -u root -pstarshield -h 127.0.0.1 -P 3307 starshield \
  < scripts/cleanup-stress-test-data.sql
```

### 9.2 爬取控制台与 Docker 的限制

Docker **API 容器内没有** `bilichat-ingest` 与 Python venv，**前端爬取控制台在纯 Docker 模式下无法拉起任务**。可选方案：

- 开发爬取功能时用 **单体后端**（§5）
- 或直接在宿主机运行 §8.2 CLI，推送目标仍为 `http://localhost:8080/api`

---

## 10. 大规模真实评论验证（可选）

> 验证 `ingest → MQ → 双引擎 → 落库 → 大屏` 全链路。

### 10.1 启动 ai-service（轻量预筛）

```bash
cd ai-service
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
PORT=5050 .venv/bin/python serve.py
```

### 10.2 以 lightweight 模式启动后端

```bash
cd starshield-backend
STARSHIELD_AI_PROVIDER=lightweight \
STARSHIELD_AI_LIGHTWEIGHT_URL=http://127.0.0.1:5050/score \
mvn spring-boot:run
```

### 10.3 运行 ingest（务必带 Cookie）

```bash
cd bilichat-ingest
.venv/bin/python ingest_comments.py \
  --bvid-file bvids.txt \
  --target-count 12000 \
  --cookie "$BILIBILI_COOKIE" \
  --rps 50 --workers 16 \
  -v --log-file run.log
```

---

## 11. 常见问题

| 现象 | 处理 |
|------|------|
| 前端表格 / 大屏空或 No Data | 看 Network 是否 500；确认 MySQL 可连、消费者无积压 |
| `Access denied`（MySQL） | 检查 `application.yml` 密码；Docker 用 `-P 3307 -pstarshield` |
| `Connection refused :5672` | 启动 RabbitMQ |
| 规则控制台空白 / 500 | 启动 **Redis**；Docker 需重建 api/worker 使 `spring.data.redis` 生效 |
| 规则控制台无默认词 | 重启后端，日志应出现「已初始化默认敏感词库 / Prompt」 |
| B 站只能抓几条评论 | 在前端或 CLI 提供含 **SESSDATA** 的 Cookie |
| 爬取控制台提交后无反应 | 确认使用**单体**后端且已安装 Python 依赖；Docker 模式见 §9.2 |
| DeepSeek 战报「AI 服务暂不可用」 | 检查 `DEEPSEEK_API_KEY`；402 表示账户余额不足 |
| 数据量异常大 / 全是 AAAA 压测 | 执行 `scripts/cleanup-stress-test-data.sql` |
| 大量 `429 Too Many Requests` | 降低 ingest `--rps` 或提高 `starshield.rate-limit.ip-qps` |
| 压测成功但库里没数据 | 上传只入队，需 worker 消费成功；看 RabbitMQ 队列与死信 |
| `Table ... doesn't exist` | 执行 `init.sql` |
| `mysqldump: Couldn't find table: crawl_task` | 本机库无此表，导出时不要包含它 |
| `npm install` 权限错误 | `npm install --cache /tmp/npm-cache` |
| 编译 `TypeTag :: UNKNOWN` | 使用 JDK 17，检查 `.mvn/toolchains.xml` |

---

## 附录 A：端口一览

| 服务 | 端口 |
|------|------|
| 后端 API（单体 / Docker 网关） | 8080 |
| 前端（Vite） | 5173 |
| ai-service（可选） | 5050 |
| MySQL（本机） | 3306 |
| MySQL（Docker 映射） | **3307** |
| Redis | 6379 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ 管理页 | 15672 |
| Elasticsearch（可选） | 9200 |

---

## 附录 B：运维脚本

| 脚本 | 用途 |
|------|------|
| `scripts/migrate-local-to-docker.sh` | 本机 3306 → Docker 3307 数据迁移 |
| `scripts/cleanup-stress-test-data.sql` | 删除压测/安全测试数据，保留 `BILI_*` 真实评论 |
| `scripts/init-es-archive-index.sh` | 初始化 ES 归档索引 |

---

## 附录 C：启用 P6 Elasticsearch 归档检索

默认 `starshield.archive.es-enabled=false`，检索走 MySQL 兜底。

### C.1 启动 Elasticsearch 8.x

Apple Silicon Mac 示例：

```bash
mkdir -p .local && cd .local
curl -LO https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.12.2-darwin-aarch64.tar.gz
tar -xzf elasticsearch-8.12.2-darwin-aarch64.tar.gz
cd ..

ES_JAVA_OPTS="-Xms1g -Xmx1g" \
.local/elasticsearch-8.12.2/bin/elasticsearch \
  -E discovery.type=single-node \
  -E xpack.security.enabled=false
```

验证：`curl http://localhost:9200`

### C.2 创建归档索引

```bash
bash scripts/init-es-archive-index.sh
```

### C.3 启用后端 ES 路径

编辑 `application.yml`：

```yaml
starshield:
  archive:
    es-enabled: true
```

验证：

```bash
curl -X POST "http://localhost:8080/api/archive/reindex?batchSize=500&maxRows=1000"
curl -s "http://localhost:8080/api/archive/search?decision=BLOCK&limit=10"
```

日志 `path=ES` 表示命中 ES；`path=MYSQL` 为降级。

---

## 附录 D：压力测试

详见 `stress-test/` 目录。

```bash
cd stress-test
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/locust -f locustfile_ingest.py --host=http://localhost:8080
```

压测会产生大量 `vu*_p*` 合成数据；联调真实评论后建议执行 `scripts/cleanup-stress-test-data.sql`。

WebSocket 大屏压测：

```bash
npm install ws
node ws_dashboard_load.js --connections 300
```
