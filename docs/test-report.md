# StarShield 压测与安全测试报告

> 测试日期：2026-06-02（压测/安全基线）；**2026-06-07（多平台接入 + Docker 联调补充）**；**2026-06-09（限流/监控/前端拆包补充）**  
> 范围：高并发摄取 / WebSocket 长连接 / 安全攻防用例 / **IdentityV-weibo & B 站直播实时弹幕**  
> 脚本目录：`stress-test/`、`secure-test/`、`bilichat-ingest/`  
> 测试者：人 + AI 协作

## TL;DR

**压测（2026-06-02）**

- ✅ **场景一（Locust 阶梯加压）**：532,393 次请求，**0 失败**，平均吞吐 **~4,449 req/s**，P99 延迟 **29 ms**，未触发业务限流（429）。
- ✅ **场景二（WebSocket 大屏）**：100 个长连接全部在线，**0 错误 / 0 断线**，20 秒内收到 387 条广播。
- ⚠️ Locust 压测机 CPU 曾超过 90%，吞吐可能受单机发压能力限制，非纯后端瓶颈。

**安全（2026-06-02）**

- ✅ **20 项用例中 14 项 PASS**：限流、SQL 注入防护、幂等性、批量校验等机制有效。
- ❌ **3 项 FAIL（CRITICAL/HIGH）**：管理接口、控制面、reindex **均无鉴权**，生产环境必须加固。
- ⚠️ **3 项 WARN**：CORS 通配、XSS 展示层、接入层缺字段校验。

**多平台接入 & Docker 联调（2026-06-07 补充）**

- ✅ **IdentityV-weibo 导入脚本**：HF 数据集下载、JSONL 转换、`platform=WEIBO` 推送链路实现完成。
- ✅ **B 站直播实时弹幕**：WebSocket 协议（op7 认证 / op5 DANMU_MSG）实现；`--live-mode realtime` 即时 push。
- ✅ **Docker 全栈修复后可用**：`docker compose up -d --build` → gateway `:8080` 返回 metrics；ingest/api/worker **liveness healthy**。
- ⚠️ **双 MySQL 隔离**：Docker MySQL 映射 **宿主机 3307**，与本机 Homebrew **3306** 数据不共享，易误判「历史数据丢失」。
- ⚠️ **直播任务**：仅开播房间有弹幕；关播时 `total=0`。
- ⚠️ **CrawlTask 控制台 + 纯 Docker**：API 容器内缺少宿主机 `bilichat-ingest`，控制台拉起 Python 任务需 CLI 或后续 sidecar。

**修复回归（2026-06-08）**

- ✅ **规则引擎 BloomFilter 漏审修复**：按敏感词长度扫描候选子串后再精确匹配，测试不再接受假阴性。
- ✅ **鉴权加固已落地**：`/api/admin/**`、`/api/crawl/**`、控制面写操作、`POST /api/archive/reindex` 需管理令牌。
- ✅ **接入层校验与 HTTP 语义**：上传请求使用 `@Valid`，`Result.code` 会同步映射为 HTTP 400 / 409 / 429 等状态码。
- ✅ **XSS 展示层修复**：Dashboard / BanAnalytics / AdminReview 等用户内容使用 Vue 文本插值或转义工具；CrawlConsole 任务详情弹窗已移除 `dangerouslyUseHTMLString`，改用 VNode 文本渲染。
- ✅ **接入限流分布式化**：`IngestionRateLimiterService` 已从 JVM 内存窗口改为 Redis Lua 滑动窗口，多个 ingest 实例共享全局 / IP / player 计数；也可切换 `token-bucket`，Redis 故障时默认本地令牌桶降级。
- ✅ **默认测试可本地运行**：`mvn test` 默认不连接 Redis / RabbitMQ / MySQL / DeepSeek；真实集成测试需 `STARSHIELD_RUN_INTEGRATION_TESTS=true` 显式开启。
- ⚠️ **仍需重跑 secure-test**：下方 2026-06-02 安全 FAIL/WARN 表保留为历史基线，需在最新代码与目标部署形态下重新生成正式结论。

**工程化补充（2026-06-09）**

- ✅ **Prometheus/Grafana 接入**：Docker Compose 新增 Prometheus `:9090`、Grafana `:3000`，抓取 ingest/api/worker `/actuator/prometheus`，预置 StarShield Observability 看板。
- ✅ **业务指标**：新增接入 QPS、限流次数、MQ 投递结果、RabbitMQ 队列深度、消费延迟、消费处理耗时。
- ✅ **前端路由级拆包**：`App.vue` 改为路径路由 + dynamic import，Vite 构建页面业务 chunk 约 5-14KB；Element 相关 JS 降至约 446KB，ECharts 按需 chunk 约 522KB，PDF 导出库改为点击时加载。

📌 2026-06-02 为**本地单体**压测/安全基线；2026-06-07 为 **Docker 微服务 + 新 ingest 模块** 联调记录；2026-06-08 为代码修复回归说明；2026-06-09 为限流、监控与前端构建优化说明。

---

## 1. 测试环境

| 组件 | 端口 | 配置摘要 |
|------|------|----------|
| starshield-backend / gateway | 8080 | Docker：Nginx → ingest（upload）/ api（其余） |
| MySQL（Docker） | **3307**（宿主机）→ 3306（容器） | 卷 `starshield_mysql_data`；与本机 3306 **独立** |
| MySQL（本机 Homebrew） | 3306 | 历史 `mvn spring-boot:run` 数据可能在此 |
| RabbitMQ | 5672 | 消费者并发 5~20，prefetch 10，手动 ACK |
| Redis | 6379 | 限流 / 敏感词 / 幂等键 |
| Prometheus | 9090 | 抓取后端 JVM 指标与 StarShield 业务指标 |
| Grafana | 3000 | StarShield Observability 看板，默认 `admin/starshield` |
| 前端 Vite | 5173 | 代理 `/api`、`/ws` → 8080 |
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
| Node.js + ws | 8.20.1 | `stress-test/ws_dashboard_load.js`、`stress-test/ws_dashboard_benchmark.js` |
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

1000+ 大屏连接基准建议使用不阻塞事件循环的专用脚本：

```bash
node ws_dashboard_benchmark.js --host ws://127.0.0.1:8080 --connections 1000 --ramp-ms 2 --duration 60
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

### 3.4 2026-06-09 优化后 1000 连接补测

**背景**：大屏 WebSocket 建连后已改为立即推送一次当前快照，定期广播仍保持默认 5 秒。为避免客户端脚本的慢客户端 busy-wait 放大延迟，本次使用 `ws_dashboard_benchmark.js`，拆分统计建连耗时与首包耗时。

**启动命令**：

```bash
cd stress-test
node ws_dashboard_benchmark.js --host ws://127.0.0.1:8080 --connections 1000 --ramp-ms 2 --duration 60
```

**结果汇总**：

| 指标 | 数值 |
|------|------|
| 尝试建连 | 1000 / 1000 |
| 成功打开 | 1000 |
| 最终在线 | 1000 |
| 首包完成 | 1000 |
| 累计断线 | 0 |
| 连接/消息错误 | 0 |
| 累计收到消息 | 12000 |
| 稳态消息吞吐 | 约 200 msg/s（1000 连接 × 5 秒一轮广播） |

**延迟分位数**：

| 指标 | P50 | P95 | P99 |
|------|-----|-----|-----|
| WebSocket open latency | 3 ms | 71 ms | 159 ms |
| 首包延迟（open → first message） | 4 ms | 121 ms | 210 ms |
| 总首包耗时（create → first message） | 7 ms | 200 ms | 271 ms |

**结论**：

1. **1000 长连接稳定**：全量连接成功，0 断开、0 错误。
2. **首包延迟已明显改善**：`open → first message` P50 仅 4 ms，说明建连立即推送快照生效，不再等待 5 秒定时广播。
3. **广播吞吐符合 5 秒周期**：稳态约 200 msg/s，对应 1000 个连接每 5 秒收到一轮广播。
4. **后续建议**：继续压测 2000 / 5000 连接时同步观察 Docker CPU、JVM 堆、Nginx worker 连接数与系统 `ulimit -n`。

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
| SEC-INJ-03 | XSS Payload 上传 | MEDIUM | ✅ FIXED | 展示层已转义；CrawlConsole 任务详情改为 VNode 文本渲染，待重跑 secure-test 确认为 PASS |
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
| P2 | XSS 输出编码 | ✅ 已完成：Dashboard / Archive / CrawlConsole 前端渲染层已统一文本渲染或转义 |
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

---

# 第三部分：多平台接入与 Docker 联调（2026-06-07）

## 9. 测试背景

在 B 站视频评论 1.2 万条 E2E（见 `P10_QA_Test/2026-05-11-bilibili-12k-e2e-verify.md`）之后，扩展：

1. **IdentityV-weibo**（第五人格官方微博评论，~3.1 万条，CC BY 4.0）
2. **B 站直播实时弹幕**（WebSocket，替代 gethistory 快照）
3. **Docker Compose 全栈**部署与前端联调

## 10. IdentityV-weibo 导入

### 10.1 脚本与数据格式

| 项 | 说明 |
|----|------|
| 脚本 | `bilichat-ingest/ingest_identityv_weibo.py` |
| 数据源 | https://huggingface.co/datasets/JaydenChao101/IdentityV-weibo |
| 入库字段 | `playerId=WEIBO_{user}_{comment_id}`、`content`、`platform=WEIBO`、`createTime`（可选） |

### 10.2 执行命令

```bash
cd bilichat-ingest
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt

# 下载 + 导出 + 推送
.venv/bin/python ingest_identityv_weibo.py --target-count 5000 --rps 20

# 仅续推（后端已启动）
.venv/bin/python ingest_identityv_weibo.py --skip-export --out-jsonl data/identityv_weibo.jsonl --rps 20
```

### 10.3 结果

| 检查项 | 结果 |
|--------|------|
| HF 下载 `weibo_dataset.jsonl` | ✅ ~17MB |
| JSONL 导出（strip HTML） | ✅ 抽样 3 条格式正确 |
| 后端未启动时推送 | ❌ `Connection refused :8080`（预期行为） |
| Docker 就绪后推送 | ⚠️ 需用户本地执行续推命令验证 WEIBO 计数 |

## 11. B 站直播实时弹幕

### 11.1 实现要点

| 模块 | 文件 |
|------|------|
| WebSocket 客户端 | `bilichat-ingest/bili_live_realtime.py` |
| CLI 入口 | `ingest_comments.py --type live --live-mode realtime` |
| 平台枚举 | `BILIBILI_LIVE` |

### 11.2 执行命令

```bash
.venv/bin/python ingest_comments.py \
  --type live \
  --live-mode realtime \
  --live-room-id <正在直播的房间号> \
  --target-count 100 \
  --rps 20
```

### 11.3 结果

| 检查项 | 结果 |
|--------|------|
| room_init + getDanmuInfo | ✅ |
| WSS 认证（op8 回复） | ✅（已修复按 operation 解析） |
| 关播房间 | ⚠️ total=0，任务报 error |
| 多房间并行监听 | ✅ 线程 + 去重 |

## 12. Docker 全栈联调

### 12.1 修复项（2026-06-07）

| 问题 | 修复 |
|------|------|
| Maven toolchain 缺失 | Dockerfile 生成 toolchains.xml |
| 3306 端口冲突 | compose 映射 **3307:3306** |
| ingest/api 启动失败 | `@EnabledOnMode` 调整；API 补 Bean |
| gateway 依赖 unhealthy | healthcheck → `/actuator/health/liveness` |

### 12.2 验证命令

```bash
docker compose up -d --build
curl -s http://127.0.0.1:8080/api/dashboard/metrics
cd starshield-frontend && npm run dev   # http://localhost:5173
```

### 12.3 观测结果（修复后）

| 指标 | 数值/状态 |
|------|-----------|
| `/api/dashboard/metrics` | code=200 |
| `total`（Docker MySQL） | 11762（主要为 BILIBILI 历史/导入） |
| `blocked` / `review` | 93 / 254 |
| 容器状态 | ingest、api、worker、gateway Up；healthcheck healthy |

## 13. 2026-06-07 结论与建议

| 优先级 | 项 | 说明 |
|--------|-----|------|
| P0 | 明确 MySQL 数据源 | 文档标注 Docker 3307 vs 本机 3306；提供 mysqldump 迁移示例 |
| P1 | WEIBO 导入完成验证 | 推送后 `SELECT platform,COUNT(*) ...` 确认 WEIBO 条数 |
| P1 | Docker 内 CrawlTask | 挂载 `bilichat-ingest` 卷或独立 ingest-worker 容器 |
| P2 | 直播断线重连 | 长时监听稳定性 |
| P2 | 重跑 secure-test | Docker 模式下鉴权 FAIL 项仍为 P0 |

## 14. 相关 ai-logs

- [P1 Phase 6](../.ai-logs/P1_Lead/2026-06-07-phase6-multi-platform-ingest.md)
- [P2 Docker 修复](../.ai-logs/P2_DevOps/2026-06-07-docker-compose-fixes-and-mysql-port.md)
- [P10 多平台 QA](../.ai-logs/P10_QA_Test/2026-06-07-multi-platform-ingest-and-docker-e2e.md)
- [P8 爬取控制台](../.ai-logs/P8_Admin_FE/2026-06-07-crawl-console-multi-platform.md)
