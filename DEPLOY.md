# StarShield 本地部署说明

面向 macOS / Linux 本地开发，按顺序执行即可。

---

## 1. 环境要求

| 依赖 | 说明 |
|------|------|
| JDK 17 | `java -version` |
| Maven 3.8+ | `mvn -version`（项目可用 Toolchains 固定 JDK 17，见 `.mvn/toolchains.xml`） |
| Node.js 18+ / npm | `node -v` |
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

1. 编辑 `starshield-backend/src/main/resources/application.yml`：将 **`spring.datasource.password`** 改为本机 MySQL root 密码；若 RabbitMQ / Redis 非本机默认端口，同步修改对应段。
2. 启动：

```bash
cd starshield-backend
mvn spring-boot:run
# 或：mvn clean package -DskipTests && java -jar target/starshield-backend-1.0.0-SNAPSHOT.jar
```

控制台出现星盾启动横幅且进程监听 **8080** 即成功。

**快速自检：**

```bash
curl -s http://localhost:8080/api/dashboard/metrics | head -c 200
# 期望为 JSON，且含 "code":200（需 MySQL 正常、依赖已就绪）
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

## 7. 常见问题

| 现象 | 处理 |
|------|------|
| 前端表格 / 大屏全是空或 No Data | 打开开发者工具 **Network**：若接口为 **500**，先看后端日志；数据库接口需 **MyBatis-Plus 正确依赖**（`mybatis-plus-spring-boot3-starter`），并确认 MySQL 可连。 |
| `Access denied`（MySQL） | 检查 `application.yml` 中用户名、密码、库名 `starshield`。 |
| `Connection refused :5672` | 启动 RabbitMQ。 |
| 规则控制台报错 / 无数据 | 启动 **Redis**；未配置时部分接口会失败。 |
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
| MySQL | 3306 |
| Redis | 6379 |
| RabbitMQ AMQP | 5672 |
| RabbitMQ 管理页 | 15672 |
| Elasticsearch（可选） | 9200 |
