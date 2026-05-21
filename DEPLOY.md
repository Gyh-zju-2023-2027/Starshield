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

---

## 附录：启用 P6 Elasticsearch 归档检索

默认配置下 `starshield.archive.es-enabled=false`，归档检索走 MySQL 兜底。若要测试 P6 的 ES 检索、聚合分析与双写链路：

### 1. 启动 Elasticsearch 8.x

无需 Docker。推荐使用 Elasticsearch 官方 `.tar.gz` 归档包启动一个本地单节点 ES 8.x，配置关闭安全认证，方便本地联调。

**1.1 下载并解压 Elasticsearch 8.12.2**

Apple Silicon（M1/M2/M3）Mac：

```bash
mkdir -p .local
cd .local

curl -LO https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.12.2-darwin-aarch64.tar.gz
tar -xzf elasticsearch-8.12.2-darwin-aarch64.tar.gz
cd ..
```

如果 `curl` 下载很慢，可以中断后使用 `aria2` 多连接断点续传：

```bash
brew install aria2

cd .local
aria2c -c -x 16 -s 16 -k 1M \
  -o elasticsearch-8.12.2-darwin-aarch64.tar.gz \
  https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.12.2-darwin-aarch64.tar.gz
tar -xzf elasticsearch-8.12.2-darwin-aarch64.tar.gz
cd ..
```

Intel Mac：

```bash
mkdir -p .local
cd .local

curl -LO https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.12.2-darwin-x86_64.tar.gz
tar -xzf elasticsearch-8.12.2-darwin-x86_64.tar.gz
cd ..
```

Intel Mac 的 `aria2` 加速下载命令：

```bash
brew install aria2

cd .local
aria2c -c -x 16 -s 16 -k 1M \
  -o elasticsearch-8.12.2-darwin-x86_64.tar.gz \
  https://artifacts.elastic.co/downloads/elasticsearch/elasticsearch-8.12.2-darwin-x86_64.tar.gz
tar -xzf elasticsearch-8.12.2-darwin-x86_64.tar.gz
cd ..
```

说明：`brew install elasticsearch` 已不适合作为本项目安装方式；Homebrew core 中没有可用的 `elasticsearch` formula，Elastic 官方 Homebrew tap 也主要用于 Elastic Stack 旧式 Homebrew 安装，不如本地归档包方式可控。

**1.2 启动本地单节点 ES**

Elasticsearch 自带兼容 JDK，通常不需要额外安装 Java。首次启动会比较慢，保持该终端不要关闭：

```bash
ES_JAVA_OPTS="-Xms1g -Xmx1g" \
.local/elasticsearch-8.12.2/bin/elasticsearch \
  -E discovery.type=single-node \
  -E xpack.security.enabled=false
```

如果提示 macOS 安全拦截，可在“系统设置 → 隐私与安全性”中允许该程序，或对解压目录执行：

```bash
xattr -dr com.apple.quarantine .local/elasticsearch-8.12.2
```

**1.3 验证 ES 是否可访问**

另开一个终端，在仓库根目录执行：

```bash
curl http://localhost:9200
```

期望能看到类似以下 JSON，且包含 `version.number`：

```json
{
  "name": "...",
  "cluster_name": "docker-cluster",
  "version": {
    "number": "8.12.2"
  }
}
```

如果提示 `Failed to connect to localhost port 9200`，说明 ES 还没有启动完成，等待几秒后重试；如果仍失败，回到 ES 启动终端查看错误日志。

如果本机磁盘剩余空间较少，ES 可能因为默认磁盘水位线不分配 shard。仅用于本地测试时，可以把水位线临时调低到 5GB 左右：

```bash
curl -X PUT "http://localhost:9200/_cluster/settings" \
  -H "Content-Type: application/json" \
  -d '{
    "transient": {
      "cluster.routing.allocation.disk.watermark.low": "10gb",
      "cluster.routing.allocation.disk.watermark.high": "5gb",
      "cluster.routing.allocation.disk.watermark.flood_stage": "1gb"
    }
  }'

curl -X POST "http://localhost:9200/_cluster/reroute?retry_failed=true"
```

### 2. 创建归档索引和别名

在仓库根目录执行：

```bash
bash scripts/init-es-archive-index.sh
```

该脚本会创建物理索引 `chat_message_archive_v1`，并绑定读写别名 `chat_message_archive`。默认使用不依赖 IK 插件的本地测试 Mapping：`chat_message_archive_v1_mapping_standard.json`。

如果本机已经存在名为 `chat_message_archive` 的物理索引，脚本会直接复用该索引作为归档索引；ES 不允许同名索引和同名别名同时存在。

如果你的 ES 已安装匹配版本的 IK 分词插件，可以改用 IK Mapping：

```bash
MAPPING_FILE=starshield-backend/src/main/resources/es/chat_message_archive_v1_mapping.json \
bash scripts/init-es-archive-index.sh
```

验证别名：

```bash
curl -s "http://localhost:9200/_cat/aliases/chat_message_archive?v"
```

如果复用了 `chat_message_archive` 物理索引，则用下面命令验证索引：

```bash
curl -s "http://localhost:9200/chat_message_archive/_count"
```

可选：如果 ES 不在默认地址，用 `ES_URL` 指定：

```bash
ES_URL=http://127.0.0.1:9200 bash scripts/init-es-archive-index.sh
```

### 3. 启用后端 ES 路径

然后编辑 `starshield-backend/src/main/resources/application.yml`：

```yaml
starshield:
  archive:
    es-enabled: true
```

启动后端后，可用以下接口验证：

```bash
# 可选：如果先通过 seed_chat_message_1000.sql 导入了 MySQL 测试数据，先回填到 ES
curl -X POST "http://localhost:8080/api/archive/reindex?batchSize=500&maxRows=1000"

curl -s "http://localhost:8080/api/archive/search?decision=BLOCK&limit=10"
curl -s "http://localhost:8080/api/archive/analysis?decision=BLOCK&topHitLimit=5"
```

后端日志出现 `path=ES` 表示命中 ES 路径；出现 `path=MYSQL` 表示 ES 未启用或查询失败，系统正在临时降级。

### 4.压力测试

#### 环境准备

##### Python 依赖

```bash
pip install -r requirements.txt
```

##### Node.js 依赖

```bash
npm install ws
```

启动终端后，进行如下测试

##### 场景一：海量并发消息摄取

**脚本**：`locustfile_ingest.py`

##### 启动方式：

```bash
locust -f locustfile_ingest.py --host=http://localhost:8080
```

浏览器打开 `http://localhost:8089`，填入并发用户数和 Spawn rate，点击 **Start swarming**。

建议参数：

| 场景     | Users | Spawn rate |
| -------- | ----- | ---------- |
| 初步摸底 | 100   | 10         |
| 中等压力 | 300   | 30         |
| 极限冲击 | 600   | 100        |

##### 场景二：大屏 WebSocket 多开长连接

**脚本**：`ws_dashboard_load.js`

##### 基本用法

```bash
# 默认 200 个连接
node ws_dashboard_load.js

# 指定连接数
node ws_dashboard_load.js --connections 500

# 含慢客户端（10 个连接人为延迟 500ms，测试后端广播背压）
node ws_dashboard_load.js --connections 500 --slow 10

# 指向非本地服务
node ws_dashboard_load.js --host ws://10.0.0.5:8080 --connections 300
```

#### 联合压测（终极场景）

同时运行场景一和场景二，模拟真实生产环境：大量玩家发言 + 多个运营大屏同时在线。

```bash
# 终端 1：Locust 摄取压测（阶梯加压）
locust -f locustfile_ingest.py --host=http://localhost:8080

# 终端 2：WebSocket 大屏多开（含慢客户端）
node ws_dashboard_load.js --connections 300 --slow 10
```

**联合观测要点**：

1. RabbitMQ `chat.message.queue` 的 Ready 数在 WS 连接建立前后是否有变化
   - 若 WS 连上后 Ready 才开始堆积 → 广播占用了消费线程资源
   - 若 WS 连上前后无差异 → 瓶颈在 DeepSeek / MySQL，与 WS 无关
2. Locust 的 P99 延迟曲线在 WS 连接数爬升期间是否抖动
   - 若抖动 → WebSocket 握手与 HTTP 请求共用了 Tomcat 线程池资源
3. 慢客户端（`--slow 10`）在线期间，后端 JVM 堆内存是否线性上涨
   - 若上涨不收敛 → `DashboardPushService` 广播缓冲区存在内存泄漏风险
