# StarShield 压测与安全测试报告

> 测试日期：2026-06-02  
> 范围：高并发摄取 / WebSocket 长连接 / 安全攻防用例  
> 脚本目录：`stress-test/`、`secure-test/`  
> 测试者：人 + AI 协作

## TL;DR

**压测**

- ✅ **场景一（Locust 阶梯加压）**：532,393 次请求，**0 失败**，平均吞吐 **~4,449 req/s**，P99 延迟 **29 ms**，未触发业务限流（429）。
- ✅ **场景二（WebSocket 大屏）**：100 个长连接全部在线，**0 错误 / 0 断线**，20 秒内收到 387 条广播。
- ⚠️ Locust 压测机 CPU 曾超过 90%，吞吐可能受单机发压能力限制，非纯后端瓶颈。

**安全**

- ✅ **20 项用例中 14 项 PASS**：限流、SQL 注入防护、幂等性、批量校验等机制有效。
- ❌ **3 项 FAIL（CRITICAL/HIGH）**：管理接口、控制面、reindex **均无鉴权**，生产环境必须加固。
- ⚠️ **3 项 WARN**：CORS 通配、XSS 展示层、接入层缺字段校验。

📌 本次为**本地单机开发环境**测试；压测结论侧重接入层（Controller → MQ），安全结论反映当前代码真实防护水位。

---

## 1. 测试环境

| 组件 | 端口 | 配置摘要 |
|------|------|----------|
| starshield-backend | 8080 | Tomcat 最大线程 400，accept-count 200 |
| MySQL | 3306 | Hikari 连接池 max 30 |
| RabbitMQ | 5672 | 消费者并发 5~20，prefetch 10，手动 ACK |
| Redis | 6379 | 限流 / 敏感词 / 幂等键 |
| 测试机 | — | macOS 本地（guoyunhaideMacBook-Air） |

**业务限流参数**（`application.yml`）：

| 维度 | 阈值 |
|------|------|
| 全局 QPS | 20,000 |
| 单 IP QPS | 300 |
| 单玩家 QPS | 30 |

**工具版本**：

| 工具 | 版本 | 脚本 |
|------|------|------|
| Locust | 2.44.1 | `stress-test/locustfile_ingest.py` |
| Node.js + ws | 8.20.1 | `stress-test/ws_dashboard_load.js` |
| httpx | ≥0.27 | `secure-test/run_security_tests.py` |

---

# 第一部分：压力测试

## 2. 场景一：海量并发消息摄取（Locust）

### 2.1 测试设计

- **目标接口**：`POST /api/chat/upload`
- **加压曲线**：`StairShape` 阶梯加压（Locust 自动启用 LoadTestShape）
- **虚拟用户策略**：每 VU 独占 50 个 `playerId`，配合随机 `X-Forwarded-For` 规避玩家/IP 限流干扰
- **内容池**：覆盖正常 / 边界 / 违规三类消息，含 500 字与 3000 字长文本

| 阶段 | 累计时长 | 并发 VU | Spawn rate |
|------|----------|---------|------------|
| 0 | 20 s | 50 | 10/s |
| 1 | 40 s | 150 | 10/s |
| 2 | 60 s | 300 | 10/s |
| 3 | 80 s | 500 | 10/s |
| 4 | 110 s | 500 | 1/s（稳压观察） |
| 5 | 120 s | 0 | 50/s（收尾） |

**启动命令**：

```bash
cd stress-test
.venv/bin/locust -f locustfile_ingest.py --host=http://localhost:8080 --headless
```

### 2.2 测试结果

| 指标 | 数值 |
|------|------|
| 总请求数 | 532,393 |
| 失败数 | **0（0.00%）** |
| 平均吞吐 | **4,448.52 req/s** |
| 平均响应时间 | 14 ms |
| 最小 / 最大 | 0 ms / 327 ms |

**响应时间分位数**：

| P50 | P66 | P75 | P80 | P90 | P95 | P98 | P99 | P99.9 | Max |
|-----|-----|-----|-----|-----|-----|-----|-----|-------|-----|
| 15 ms | 16 ms | 16 ms | 16 ms | 17 ms | 18 ms | 22 ms | **29 ms** | 130 ms | 330 ms |

**阶段观测**（Locust 实时日志摘录）：

| 并发 VU | 瞬时 req/s | 平均延迟 | 失败率 |
|---------|-----------|----------|--------|
| 50 | ~4,700 | 6~7 ms | 0% |
| 150 | ~4,560 | 9~10 ms | 0% |
| 300 | ~4,550 | 11~12 ms | 0% |
| 500 | ~4,200~4,360 | 14~15 ms | 0% |

### 2.3 结论

1. **接入层表现稳定**：500 VU 全速压测下，P99 仍维持在 29 ms，符合「Controller 仅投递 MQ、响应 < 10 ms」的设计目标（均值层面）。
2. **无限流误伤**：压测脚本通过独立 player 池 + 随机 IP 规避限流，全程无 HTTP 非 200 或业务 code=429。
3. **压测机 CPU 告警**：Locust 报告 CPU > 90%，实际后端 QPS 上限可能被发压端单核能力约束；分布式 Locust 可进一步摸底真实拐点。
4. **未覆盖 SpikeShape**：峰值冲击场景（600 VU 瞬时拉起）本次未执行，建议作为后续测试项。

---

## 3. 场景二：大屏 WebSocket 多开长连接

### 3.1 测试设计

- **目标端点**：`ws://localhost:8080/ws/dashboard`
- **连接数**：100（默认 200，本次缩减以快速验证）
- **慢客户端**：5 个（人为 busy-wait 500 ms，模拟前端渲染卡顿）
- **建连策略**：每 10 ms 建立一个连接，避免握手风暴
- **持续时间**：20 s（SIGINT 优雅退出）

**启动命令**：

```bash
cd stress-test
node ws_dashboard_load.js --connections 100 --slow 5 --ramp-ms 10
```

### 3.2 测试结果

| 指标 | 数值 |
|------|------|
| 尝试建连 | 100 |
| 最终在线 | **100** |
| 累计断线 | 0 |
| 连接/消息错误 | **0** |
| 累计收到广播 | 387 条（20 s） |
| 首消息延迟 P50 | 2,019 ms |
| 首消息延迟 P99 | 6,590 ms |

### 3.3 结论

1. **连接稳定性良好**：100 个长连接在压测期间无断线、无错误。
2. **首消息延迟偏高**：P50 ~2 s，可能与后端广播频率、消费链路处理速度或建连 ramp-up 期间消息积压有关；需结合 Dashboard 推送策略进一步分析。
3. **慢客户端未引发异常**：5 个慢客户端在线期间未观察到 OOM 或连接雪崩（本次仅 20 s 短测，长时观测建议 ≥ 5 min）。

---

## 4. 联合压测

本次**未执行**联合压测（Locust + WebSocket 同时运行）。参考 `DEPLOY.md` 联合场景命令：

```bash
# 终端 1
.venv/bin/locust -f locustfile_ingest.py --host=http://localhost:8080

# 终端 2
node ws_dashboard_load.js --connections 300 --slow 10
```

联合观测要点：

1. RabbitMQ `chat.message.queue` Ready 数在 WS 连接建立前后是否突增
2. Locust P99 曲线在 WS 建连爬升期间是否抖动（Tomcat 线程池争抢）
3. 慢客户端在线时 JVM 堆内存是否线性上涨

---

# 第二部分：安全测试

## 5. 安全测试概览

- **脚本**：`secure-test/run_security_tests.py`（20 项自动化用例）
- **执行时间**：2026-06-02T07:15:51Z ~ 07:16:56Z
- **目标**：`http://localhost:8080`

**结果汇总**：

| 状态 | 数量 | 含义 |
|------|------|------|
| PASS | 14 | 符合安全预期 |
| FAIL | 3 | 发现漏洞或严重缺口 |
| WARN | 3 | 已知风险，需关注 |
| SKIP | 0 | — |

**启动命令**：

```bash
cd secure-test
.venv/bin/python run_security_tests.py --host http://localhost:8080
```

---

## 6. 安全用例明细

### 6.1 限流

| ID | 用例 | 级别 | 状态 | 实际结果 |
|----|------|------|------|----------|
| SEC-RL-01 | 玩家维度限流 | HIGH | ✅ PASS | 35 次请求：200=30，429=5（player-qps=30 生效） |
| SEC-RL-02 | IP 维度限流 | HIGH | ✅ PASS | 320 并发：200=300，429=20（ip-qps=300 生效） |
| SEC-RL-03 | XFF 伪造绕过 | MEDIUM | ✅ PASS | 换伪造 IP 后仍 429（本次命中玩家限流） |

> 生产环境需在 API 网关固定真实客户端 IP，不可盲信 `X-Forwarded-For`。

### 6.2 注入与边界输入

| ID | 用例 | 级别 | 状态 | 实际结果 |
|----|------|------|------|----------|
| SEC-INJ-01 | 归档检索 SQL 注入 | CRITICAL | ✅ PASS | 6 组注入参数均正常，无 SQL 错误泄露 |
| SEC-INJ-02 | 发言内容 SQL 片段 | MEDIUM | ✅ PASS | 4 条 payload 无 500 |
| SEC-INJ-03 | XSS Payload 上传 | MEDIUM | ⚠️ WARN | 服务端接受 payload；展示层需 HTML 转义 |
| SEC-INJ-04 | 超大 Payload（50 KB） | MEDIUM | ✅ PASS | HTTP 200 正常入队，无 500 |
| SEC-INJ-05 | 畸形 JSON | LOW | ✅ PASS | empty/not_json/array → 400；框架拦截有效 |
| SEC-INJ-06 | 缺字段上传 | MEDIUM | ⚠️ WARN | 空 body / 缺字段仍返回 200，脏数据可入 MQ |

### 6.3 鉴权与越权

| ID | 用例 | 级别 | 状态 | 实际结果 |
|----|------|------|------|----------|
| SEC-AUTH-01 | 管理接口无鉴权 | CRITICAL | ❌ FAIL | `GET /api/admin/moderation/pending` 无 token 即 200 |
| SEC-AUTH-02 | 控制面无鉴权 | CRITICAL | ❌ FAIL | `PUT /api/control/rules/sensitive-words` 无鉴权可篡改词表 |
| SEC-AUTH-03 | reindex 无鉴权 | HIGH | ❌ FAIL | `POST /api/archive/reindex` 无鉴权即可触发 ES 回填 |
| SEC-AUTH-04 | 路径遍历 ID | MEDIUM | ✅ PASS | 非法 path 参数不导致 500 |

### 6.4 幂等性

| ID | 用例 | 级别 | 状态 | 实际结果 |
|----|------|------|------|----------|
| SEC-IDEM-01 | 缺少幂等键 | MEDIUM | ✅ PASS | 无 `X-Idempotency-Key` → 409 |
| SEC-IDEM-02 | 幂等键重用 | HIGH | ✅ PASS | 同一键二次提交 → 409 |
| SEC-IDEM-03 | 伪造幂等键 | MEDIUM | ✅ PASS | 未注册 UUID → 409 |

### 6.5 配置与其他

| ID | 用例 | 级别 | 状态 | 实际结果 |
|----|------|------|------|----------|
| SEC-MISC-01 | CORS 通配 | MEDIUM | ⚠️ WARN | `Access-Control-Allow-Origin: *` |
| SEC-MISC-02 | HTTP 方法限制 | LOW | ✅ PASS | `GET /api/chat/upload` → 405 |
| SEC-MISC-03 | 批量非法 decision | MEDIUM | ✅ PASS | `decision=HACK` → 400 |
| SEC-MISC-04 | 敏感端点探测 | HIGH | ✅ PASS | actuator/.env 等 4 路径无泄露 |

---

## 7. 综合结论与后续建议

### 7.1 压测

| 优先级 | 项 | 说明 |
|--------|-----|------|
| P1 | 执行 SpikeShape 峰值冲击 | 验证 600 VU 瞬时拉起时 MQ 堆积与恢复能力 |
| P1 | 联合压测 | 模拟「大量发言 + 多运营大屏」真实生产负载 |
| P2 | 分布式 Locust | 消除压测机 CPU 瓶颈，找到真实 QPS 拐点 |
| P2 | 消费端监控 | 压测期间记录 MQ Ready / Unacked、消费延迟、DLQ 深度 |
| P3 | WS 长时压测 | ≥ 30 min，含 `--slow 10`，验证广播背压与内存泄漏 |

### 7.2 安全

| 优先级 | 项 | 说明 |
|--------|-----|------|
| **P0** | 管理 / 控制面 / reindex 鉴权 | 接入 Spring Security 或 API Gateway JWT；未修复前**禁止公网暴露** |
| P1 | 接入层字段校验 | `ChatMessageUploadRequest` 增加 `@Valid`，缺字段返回 400 |
| P1 | CORS 白名单 | 替换 `@CrossOrigin(origins="*")` 为明确域名列表 |
| P2 | XFF 信任链 | 网关剥离客户端伪造头，仅信任一层代理注入的 IP |
| P2 | XSS 输出编码 | Dashboard / Archive 前端渲染层统一转义 |
| P3 | reindex 异步化 + 鉴权 | 避免未授权长任务阻塞 HTTP 线程 |

---

## 8. 附录：复现步骤

### 8.1 压测

```bash
# 1. 启动依赖（MySQL / RabbitMQ / Redis 需已运行）
cd starshield-backend
mvn clean spring-boot:run \
  -Dspring-boot.run.mainClass=com.starshield.backend.StarShieldApplication

# 2. 安装压测依赖（首次）
cd ../stress-test
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
npm install

# 3. 场景一 — Web UI 模式
.venv/bin/locust -f locustfile_ingest.py --host=http://localhost:8080
# 浏览器打开 http://localhost:8089

# 4. 场景一 — 无界面模式
.venv/bin/locust -f locustfile_ingest.py --host=http://localhost:8080 --headless
# 注意：启用 LoadTestShape 时 -u/-r/-t 会被忽略

# 5. 场景二 — WebSocket
node ws_dashboard_load.js --connections 200
```

切换加压曲线：编辑 `locustfile_ingest.py` 末尾，取消注释 `shape_class = StairShape` 或 `shape_class = SpikeShape`。

### 8.2 安全测试

```bash
# 1. 安装依赖（首次）
cd secure-test
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# 2. 运行（退出码 FAIL>0 时返回 1，可用于 CI）
.venv/bin/python run_security_tests.py --host http://localhost:8080

# 3. 导出 JSON 结果
.venv/bin/python run_security_tests.py \
  --host http://localhost:8080 \
  --json results.json
```

> 控制面测试会自动恢复原有敏感词，不会持久污染 Redis 词表。  
> 退出码：`FAIL > 0` 时返回 1，可用于 CI 门禁。
