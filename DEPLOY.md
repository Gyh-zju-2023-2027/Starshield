# StarShield 部署教程

> 适用环境：macOS / Linux 本地开发部署

---

## 目录

1. [环境要求](#1-环境要求)
2. [安装中间件](#2-安装中间件)
3. [初始化数据库](#3-初始化数据库)
4. [配置并启动后端](#4-配置并启动后端)
5. [启动前端](#5-启动前端)
6. [功能验证](#6-功能验证)
7. [常见问题](#7-常见问题)

---

## 1. 环境要求

| 工具 | 最低版本 | 推荐版本 | 检查命令 |
|------|----------|----------|----------|
| JDK | 17 | 17 LTS | `java -version` |
| Maven | 3.8 | 3.9 | `mvn -version` |
| Node.js | 18 | 20 LTS | `node -v` |
| npm | 9 | 10 | `npm -v` |
| MySQL | 8.0 | 8.0 | `mysql --version` |
| RabbitMQ | 3.10 | 3.13 | `rabbitmqctl version` |

---

## 2. 安装中间件

### 2.1 MySQL 8.0

**macOS（Homebrew）**

```bash
brew install mysql@8.0
brew services start mysql@8.0

# 初次安全配置（设置 root 密码）
mysql_secure_installation
```

**Linux（Ubuntu/Debian）**

```bash
sudo apt update
sudo apt install -y mysql-server
sudo systemctl start mysql
sudo systemctl enable mysql

# 设置 root 密码
sudo mysql
ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'your_password';
FLUSH PRIVILEGES;
EXIT;
```

**验证连接**

```bash
mysql -u root -p
# 输入密码后看到 mysql> 提示符即成功
```

---

### 2.2 RabbitMQ 3.x

**macOS（Homebrew）**

```bash
brew install rabbitmq
brew services start rabbitmq

# 启用 Web 管理界面插件
rabbitmq-plugins enable rabbitmq_management
```

**Linux（Ubuntu/Debian）**

```bash
# 安装 Erlang（RabbitMQ 依赖）
sudo apt install -y erlang

# 添加 RabbitMQ 官方源并安装
curl -s https://packagecloud.io/install/repositories/rabbitmq/rabbitmq-server/script.deb.sh | sudo bash
sudo apt install -y rabbitmq-server
sudo systemctl start rabbitmq-server
sudo systemctl enable rabbitmq-server

# 启用管理界面
sudo rabbitmq-plugins enable rabbitmq_management
```

**验证 RabbitMQ**

```bash
# 检查服务状态
rabbitmqctl status

# 打开 Web 管理界面（默认账号密码均为 guest）
open http://localhost:15672
```

> RabbitMQ 默认端口：
> - AMQP 连接端口：**5672**
> - Web 管理界面端口：**15672**

---

## 3. 初始化数据库

```bash
# 登录 MySQL
mysql -u root -p

# 执行初始化脚本（创建数据库 + 建表 + 索引）
SOURCE /path/to/starshield-backend/src/main/resources/init.sql;

# 验证
USE starshield;
SHOW TABLES;
# 应看到 chat_message_log 表
DESC chat_message_log;
```

或直接在终端一行执行：

```bash
mysql -u root -p starshield < starshield-backend/src/main/resources/init.sql
```

---

## 4. 配置并启动后端

### 4.1 修改配置文件

打开 `starshield-backend/src/main/resources/application.yml`，
找到以下两处并替换为你的真实配置：

```yaml
spring:
  datasource:
    # 将 YOUR_PASSWORD 替换为你的 MySQL root 密码
    password: YOUR_PASSWORD

  rabbitmq:
    # 如果修改过 RabbitMQ 的账号密码，在此处同步修改
    username: guest
    password: guest
```

### 4.2 编译并启动

```bash
cd starshield-backend

# 方式一：使用 Maven 插件直接启动（开发推荐）
mvn spring-boot:run

# 方式二：先打包再运行（接近生产环境）
mvn clean package -DskipTests
java -jar target/starshield-backend-1.0.0-SNAPSHOT.jar
```

### 4.3 验证后端启动成功

终端看到以下输出即表示启动成功：

```
╔══════════════════════════════════════════════════╗
║       星盾 StarShield 舆情监控中台已启动          ║
║       端口: 8080  环境: 本地开发                  ║
╚══════════════════════════════════════════════════╝
```

用 curl 快速测试接口是否正常：

```bash
curl -X POST http://localhost:8080/api/chat/upload \
  -H "Content-Type: application/json" \
  -d '{
    "playerId": "P1234567",
    "content": "这把操作太猛了！",
    "platform": "GAME_INNER",
    "status": 0
  }'

# 期望返回：
# {"code":200,"message":"接收成功"}
```

---

## 5. 启动前端

```bash
cd starshield-frontend

# 若依赖未安装
npm install --cache /tmp/npm-cache

# 启动开发服务器
npm run dev
```

终端输出如下即启动成功：

```
  VITE v5.x.x  ready in xxx ms

  ➜  Local:   http://localhost:5173/
  ➜  Network: use --host to expose
```

打开浏览器访问：**http://localhost:5173**

> **注意**：`vite.config.js` 中已配置 proxy，前端 `/api` 请求会自动转发到后端 `http://localhost:8080`，无需手动处理跨域。

---

## 6. 功能验证

### 6.1 压测控制台使用

1. 打开 `http://localhost:5173`
2. 在「发射参数配置」区域设置：
   - **并发请求数**：推荐先用 100 测试，再加到 1000
   - **批次大小**：默认 50，可适当调大
   - **来源平台**：选择任意平台
3. 点击「**模拟发送 N 条并发日志**」按钮
4. 观察「实时统计」面板中的 **成功数 / 失败数 / QPS / 成功率**
5. 查看「请求日志」区域了解每批次详情

### 6.2 验证数据已写入 MySQL

```sql
USE starshield;

-- 查看最新写入的 10 条记录
SELECT id, player_id, content, platform, status, create_time
FROM chat_message_log
ORDER BY create_time DESC
LIMIT 10;

-- 查看总记录数
SELECT COUNT(*) AS total FROM chat_message_log;

-- 按平台统计
SELECT platform, COUNT(*) AS cnt
FROM chat_message_log
GROUP BY platform;
```

### 6.3 验证 RabbitMQ 消息流转

打开 RabbitMQ 管理界面：`http://localhost:15672`（账号密码均为 guest）

- **Exchanges** → 应看到 `chat.direct.exchange` 和 `chat.dl.exchange`
- **Queues** → 应看到 `chat.message.queue` 和 `chat.message.dlq`
- 压测时观察队列的 **Messages Ready** 和 **Message rates** 折线图

---

## 7. 常见问题

### Q1：后端启动报错 `Access denied for user 'root'@'localhost'`

**原因**：`application.yml` 中的数据库密码未修改或密码错误。

**解决**：
```yaml
# application.yml
spring:
  datasource:
    password: 你的真实MySQL密码  # 替换此处
```

---

### Q2：后端启动报错 `Connection refused: localhost/127.0.0.1:5672`

**原因**：RabbitMQ 服务未启动。

**解决**：
```bash
# macOS
brew services start rabbitmq

# Linux
sudo systemctl start rabbitmq-server
```

---

### Q3：前端点击按钮后全部请求失败，控制台报 `Network Error`

**原因**：后端服务未启动，或 Vite proxy 配置有误。

**解决**：
1. 确认后端已启动并监听 8080 端口：`curl http://localhost:8080/api/chat/upload`
2. 检查 `vite.config.js` 中 proxy target 是否为 `http://localhost:8080`

---

### Q4：MySQL 表不存在，报错 `Table 'starshield.chat_message_log' doesn't exist`

**原因**：数据库初始化脚本未执行。

**解决**：
```bash
mysql -u root -p < starshield-backend/src/main/resources/init.sql
```

---

## 8. 联调启动顺序（2026-04 版本）

建议严格按以下顺序启动：

1. 启动 MySQL（确认 `starshield` 库存在）
2. 启动 RabbitMQ（确认 `5672` 可连通）
3. 启动 Redis（确认 `6379` 可连通）
4. （可选）启动 Elasticsearch（确认 `9200` 可连通）
5. 启动后端 `starshield-backend`
6. 启动前端 `starshield-frontend`

### 联调检查清单

- 接入与削峰：
  - 调 `POST /api/chat/upload` 返回 `200`
  - 高频请求可触发 `429`
- 审核链路：
  - 消费后 `chat_message_log` 包含 `decision/risk_score/labels/hit_words`
- 控制台：
  - `GET/PUT /api/control/rules/sensitive-words` 可读写
  - `GET/PUT /api/control/prompt` 可读写
- 审核后台：
  - `GET /api/admin/moderation/pending` 有返回
  - `POST /confirm-ban`、`/release` 需带 `X-Idempotency-Key`
  - `GET /api/admin/moderation/{id}/audit-logs` 可看到操作时间线
- 大屏：
  - `GET /api/dashboard/metrics` 正常
  - WebSocket `/ws/dashboard` 可收到推送
- 检索：
  - `GET /api/archive/search?page=1&limit=50` 正常

### Q5：npm install 报权限错误 `EPERM`

**原因**：npm 缓存目录被 root 账户锁定。

**解决**：使用自定义缓存目录绕过：
```bash
npm install --cache /tmp/npm-cache
```

---

### Q6：消息发送成功但 MySQL 中没有数据

**排查步骤**：
1. 查看后端控制台日志，搜索 `[消息消费]` 关键字
2. 登录 RabbitMQ 管理界面，检查 `chat.message.queue` 是否有积压消息
3. 检查 `chat.message.dlq` 死信队列中是否有消息（有则表示消费失败）
4. 后端日志中搜索 `ERROR` 关键字排查具体异常

---

## 附录：端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 8080 | REST API 服务 |
| Vue 前端开发服务 | 5173 | Vite Dev Server |
| MySQL | 3306 | 数据库 |
| RabbitMQ AMQP | 5672 | 消息队列连接 |
| RabbitMQ 管理界面 | 15672 | Web 控制台 |
