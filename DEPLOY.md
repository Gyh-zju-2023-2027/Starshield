# StarShield 本地部署说明

面向 macOS / Linux 本地开发。本文分为两部分：

- `基础部署`：后端 + 前端 + 中间件，可完成常规联调
- `大规模真实评论验证（可选）`：`bilichat-ingest` + `ai-service` 全链路压测

---

## 1. 环境要求

| 依赖 | 说明 |
|------|------|
| JDK 17 | `java -version` |
| Maven 3.8+ | `mvn -version`（项目可用 Toolchains 固定 JDK 17，见 `.mvn/toolchains.xml`） |
| Node.js 18+ / npm | `node -v` |
| Python 3.10+（可选） | `python3 --version`（`ai-service`、`bilichat-ingest` 需要） |
| MySQL 8 | `mysql --version` |
| RabbitMQ 3.x | 消息入队与消费 |
| Redis | **规则控制台**（敏感词 / Prompt）必需 |

---

## 2. 安装并启动中间件

**macOS（Homebrew）示例：**

```bash
brew install mysql@8.0 rabbitmq redis
brew services start mysql@8.0
brew services start rabbitmq
brew services start redis

rabbitmq-plugins enable rabbitmq_management   # 可选：Web 管理 http://localhost:15672 ，账号 guest/guest
```

**Linux：** 用发行版包管理器安装 `mysql-server`、`rabbitmq-server`、`redis-server` 并 `systemctl start` 即可。

---

## 3. 数据库

**首次建库建表**（在仓库根目录执行，路径按实际调整）：

```bash
mysql -u root -p < starshield-backend/src/main/resources/init.sql
```

**可选：导入测试数据**（审核后台 / 大屏联调，`chat_message_log` 约 1000 条）：

```bash
mysql -u root -p starshield < starshield-backend/src/main/resources/seed_chat_message_1000.sql
```

若库已存在但表结构偏旧、缺 `decision` 等列，可先执行 `migrate_chat_message_log.sql`，或删库后重新执行 `init.sql`。

---

## 4. 后端配置与启动

1. 编辑 `starshield-backend/src/main/resources/application.yml`：
   - 将 **`spring.datasource.password`** 改为本机 MySQL 密码
   - 若 RabbitMQ / Redis 非本机默认端口，同步修改对应段
   - 若要接入轻量模型服务，设置 `starshield.ai.provider=lightweight`，并配置 `starshield.ai.lightweight-url`
2. 启动：

```bash
cd starshield-backend
mvn spring-boot:run
# 或：mvn clean package -DskipTests && java -jar target/starshield-backend-1.0.0-SNAPSHOT.jar
```

控制台出现星盾启动横幅且进程监听 **8080** 即成功。

**快速自检：**

```bash
curl -s http://localhost:8080/api/dashboard/metrics
# 期望返回 JSON，且含 "code":200（需 MySQL 正常、依赖已就绪）
```

---

## 5. 前端

```bash
cd starshield-frontend
npm install
npm run dev
```

浏览器打开 **http://localhost:5173**。`vite.config.js` 已将 **`/api` 与 `/ws` 代理到 8080**，请始终通过该地址访问，勿直接打开打包后的 `index.html` 以免接口跨域失败。

---

## 6. 推荐启动顺序

1. MySQL → 2. Redis → 3. RabbitMQ → 4. 后端 → 5. 前端  

（Elasticsearch 默认关闭，检索走 MySQL；若启用需改 `application.yml` 与 `starshield.archive.es-enabled`。）

---

## 7. 大规模真实评论验证（可选）

> 目标：使用 `bilichat-ingest` 抓取 B 站评论并推送到 StarShield，验证
> `MQ → Consumer → 引擎A/B → 落库 → 大屏` 全链路。

### 7.1 启动 ai-service（轻量模型）

```bash
cd ai-service
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# 默认使用 5050 端口（与项目报告/脚本一致）
PORT=5050 .venv/bin/python serve.py
```

### 7.2 以 lightweight 模式启动后端

```bash
cd starshield-backend
STARSHIELD_AI_PROVIDER=lightweight \
STARSHIELD_AI_LIGHTWEIGHT_URL=http://127.0.0.1:5050/score \
mvn spring-boot:run
```

### 7.3 运行 ingest 脚本

```bash
cd bilichat-ingest
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

.venv/bin/python ingest_comments.py \
  --bvid-file bvids.txt \
  --target-count 12000 \
  --rps 50 --workers 16 \
  -v --log-file run.log
```

### 7.4 观察链路结果

```bash
cd bilichat-ingest
.venv/bin/python verify_pipeline.py --watch
```

---

## 8. 常见问题

| 现象 | 处理 |
|------|------|
| 前端表格 / 大屏全是空或 No Data | 打开开发者工具 **Network**：若接口为 **500**，先看后端日志；数据库接口需 **MyBatis-Plus 正确依赖**（`mybatis-plus-spring-boot3-starter`），并确认 MySQL 可连。 |
| `Access denied`（MySQL） | 检查 `application.yml` 中用户名、密码、库名 `starshield`。 |
| `Connection refused :5672` | 启动 RabbitMQ。 |
| 规则控制台报错 / 无数据 | 启动 **Redis**；未配置时部分接口会失败。 |
| 大量 `429 Too Many Requests` | 降低 ingest `--rps`；或提高 `starshield.rate-limit.ip-qps`（默认 300）。 |
| `verify_pipeline` 一直无增量 | 检查后端消费者日志、RabbitMQ 队列积压与死信队列。 |
| 使用轻量模型但命中率异常低 | 检查 `STARSHIELD_AI_PROVIDER=lightweight` 与 `STARSHIELD_AI_LIGHTWEIGHT_URL` 是否生效。 |
| 压测成功但库里没数据 | 上传只入队，需 **消费者** 消费成功才落库；看 RabbitMQ 队列是否积压或进死信。 |
| `Table ... doesn't exist` | 执行 `init.sql`。 |
| `npm install` 权限错误 | `npm install --cache /tmp/npm-cache` |
| 编译报 `TypeTag :: UNKNOWN` 等 | 使用 **JDK 17** 构建，并检查 `.mvn/toolchains.xml` 中 `jdkHome`。 |

---

## 附录：端口一览

| 服务 | 端口 |
|------|------|
| 后端 API | 8080 |
| 前端（Vite） | 5173 |
| ai-service（可选） | 5050（可通过 `PORT` 覆盖） |
| MySQL | 3306 |
| Redis | 6379 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ 管理页 | 15672 |
| Elasticsearch（可选） | 9200 |
