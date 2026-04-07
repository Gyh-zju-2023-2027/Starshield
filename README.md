# ⬡ StarShield 星盾

> 海量游戏玩家发言舆情与违规智能监控中台

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=openjdk)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6db33f?style=flat-square&logo=springboot)
![Vue](https://img.shields.io/badge/Vue-3.4-42b883?style=flat-square&logo=vue.js)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.x-ff6600?style=flat-square&logo=rabbitmq)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479a1?style=flat-square&logo=mysql)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)

---

## 项目简介

StarShield（星盾）是一套面向**千万级 QPS** 场景设计的游戏玩家发言舆情监控系统。

系统通过「**高速接收 → MQ 削峰 → 异步落盘 → AI 智能分析**」四级流水线，实现对海量玩家发言数据的实时采集、存储与违规检测，为游戏运营团队提供可观测、可扩展的内容安全基础设施。

```
前端发言数据流入
        │
        ▼
┌───────────────────┐
│  ChatMessageController │  ← 仅做 MQ 投递，响应 < 10ms
└────────┬──────────┘
         │  convertAndSend
         ▼
┌─────────────────────────┐
│   RabbitMQ Exchange     │  ← 流量缓冲，削峰填谷
│   chat.direct.exchange  │
└────────┬────────────────┘
         │  routing key
         ▼
┌────────────────────┐
│  chat.message.queue │
└────────┬───────────┘
         │  5~20 并发消费者
         ▼
┌─────────────────────────┐
│   ChatMessageConsumer   │  ← 解析 → AI 分析 → 落盘
└────────┬────────────────┘
         │
         ▼
┌─────────────────────────┐
│   MySQL  (InnoDB)       │  ← chat_message_log 表
└─────────────────────────┘
```

---

## 技术栈

| 层次 | 技术选型 | 说明 |
|------|----------|------|
| 接入层 | Spring Boot 3.2 + Tomcat | 400 线程池，抗高并发接入 |
| 消息队列 | RabbitMQ 3.x | Direct Exchange + 死信队列，At-Least-Once 语义 |
| 持久层 | MySQL 8.0 + MyBatis-Plus | 雪花算法主键，4 个业务索引 |
| AI 分析 | `AiAnalysisService`（预留） | 可无缝接入 GPT-4o / 通义千问 / 文心一言 |
| 前端 | Vue 3 + Vite + Element Plus | 压测控制台，分批 `Promise.all` 并发 |
| 构建 | Maven 3.x / npm | 标准工程化配置 |

---

## 项目结构

```
StarShield/
├── starshield-backend/                    # Spring Boot 后端工程
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/starshield/backend/
│       │   ├── StarShieldApplication.java  # 启动入口
│       │   ├── common/
│       │   │   └── Result.java             # 全局统一返回格式 Result<T>
│       │   ├── config/
│       │   │   ├── RabbitMQConfig.java     # 交换机、队列、死信队列声明
│       │   │   └── MyBatisPlusConfig.java  # 字段自动填充
│       │   ├── entity/
│       │   │   └── ChatMessageLog.java     # 核心实体（雪花ID）
│       │   ├── mapper/
│       │   │   └── ChatMessageLogMapper.java
│       │   ├── service/
│       │   │   ├── AiAnalysisService.java  # AI 分析服务（预留扩展点）
│       │   │   ├── ChatMessageService.java
│       │   │   └── impl/ChatMessageServiceImpl.java
│       │   ├── controller/
│       │   │   └── ChatMessageController.java  # POST /api/chat/upload
│       │   └── consumer/
│       │       └── ChatMessageConsumer.java    # MQ 消费者，手动 ACK
│       └── resources/
│           ├── application.yml             # 完整配置文件
│           └── init.sql                    # 数据库初始化脚本
│
├── starshield-frontend/                   # Vue 3 前端工程
│   ├── package.json
│   ├── vite.config.js
│   ├── index.html
│   └── src/
│       ├── main.js
│       ├── App.vue
│       ├── api/
│       │   └── chat.js                    # axios 封装
│       └── views/
│           └── TestMock.vue               # 并发压测控制台
│
├── README.md
└── DEPLOY.md
```

---

## 核心设计亮点

### 1. 高并发削峰填谷

`ChatMessageController` 接口不触碰任何 DB / AI 操作，只做一件事：**序列化 → 投递 MQ → 返回 200**。
理论响应时间 < 10ms，单机 QPS 可达 10,000+。

```java
// 核心逻辑精简示意
rabbitTemplate.convertAndSend(CHAT_EXCHANGE, CHAT_ROUTING_KEY, messageJson);
return Result.success("接收成功", null);
```

### 2. 可靠消息投递（At-Least-Once）

- 队列和交换机均设置 `durable=true`，RabbitMQ 重启不丢消息
- 消费者使用**手动 ACK**，业务处理成功才确认，失败则 NACK 转入死信队列
- 死信队列（`chat.message.dlq`）兜底，防止「毒消息」堵塞主队列

### 3. AI 分析预留扩展点

`AiAnalysisService.analyze(String content)` 当前返回占位结果。
后续只需替换此方法的实现，调用方（消费者）无需任何改动：

```java
// 接入示例（通义千问）
public String analyze(String content) {
    // 调用 DashScope SDK
    Generation gen = new Generation();
    Message msg = Message.builder().role(Role.USER.getValue())
        .content("请判断以下游戏发言是否违规：" + content).build();
    GenerationResult result = gen.call(...);
    return result.getOutput().getText();
}
```

### 4. 前端分批并发压测

`TestMock.vue` 采用「分批 `Promise.all`」而非一次性发送全部请求，
规避浏览器 TCP 连接数限制，模拟真实高并发场景：

```js
for (let i = 0; i < total; i += batchSize) {
  const batch = requests.slice(i, i + batchSize)
  await Promise.all(batch)  // 每批真正并发，批次间无延迟
}
```

---

## 快速启动

详细步骤请参阅 [DEPLOY.md](./DEPLOY.md)。

```bash
# 1. 初始化数据库
mysql -u root -p < starshield-backend/src/main/resources/init.sql

# 2. 启动后端（修改 application.yml 中的数据库密码后）
#    首次克隆请检查 starshield-backend/.mvn/toolchains.xml 里的 jdkHome 是否为你本机 JDK17
cd starshield-backend && mvn spring-boot:run

# 3. 启动前端
cd starshield-frontend && npm run dev

# 4. 打开浏览器
open http://localhost:5173
```

---

## 数据库表设计

```sql
CREATE TABLE chat_message_log (
    id                 BIGINT      NOT NULL  COMMENT '雪花算法主键',
    player_id          VARCHAR(64) NOT NULL  COMMENT '玩家ID',
    content            TEXT        NOT NULL  COMMENT '发言原文',
    platform           VARCHAR(32) NOT NULL  COMMENT '来源平台',
    status             TINYINT     NOT NULL  COMMENT '0待处理 1正常 2违规',
    ai_analysis_result TEXT                  COMMENT 'AI分析结果JSON',
    create_time        DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_platform    (platform),
    INDEX idx_player_id   (player_id),
    INDEX idx_status      (status),
    INDEX idx_create_time (create_time)
);
```

---

## 后续规划（Phase 2）

- [ ] 接入真实 AI 大模型（GPT-4o / 通义千问）进行违规检测
- [ ] 完成 Elasticsearch 原生 DSL 检索（替换当前 ES 快速路径）
- [ ] 增加审计日志检索页与操作统计看板
- [ ] 接入 Prometheus + Grafana 监控 QPS / 消费延迟
- [ ] 支持 Kubernetes 水平扩容部署

---

## License

MIT © 2026 StarShield
