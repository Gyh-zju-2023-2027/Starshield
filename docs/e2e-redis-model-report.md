# StarShield Redis + 模型判断 端到端测试报告

> 测试日期：2026-04-28
> 范围：`POST /api/chat/upload` → MQ → Consumer → 引擎A(Redis) + 引擎B(轻量+DeepSeek) → 合并 → MySQL → Dashboard
> 测试者：人 + AI 协作

## TL;DR

- ✅ 整条「Redis 敏感词热更新 → 引擎 A → 引擎 B（轻量+真实 DeepSeek）→ 合并 → 落库 → 大屏」端到端**已经跑通**。
- 🐞 测试中发现两个 BUG，**BUG#1 已在本次测试中修复并验证**；**BUG#2（PR#2 引入）需要再次修复**。
- 📝 另有一处 LLM 路径 `riskScore` 语义问题（非阻塞），建议改进。

---

## 1. 测试环境

| 组件 | 端口 | 备注 |
|---|---|---|
| Redis | 6379 | 默认 |
| RabbitMQ | 5672 / 15672 | guest/guest |
| MySQL | 3306 | root/`<本地>`，库 `starshield` |
| ai-service (Flask `/score`) | **5050** | 本机 5000 被 macOS AirPlay (`AirTunes/870.14.1`) 占用，需让 ai-service 跑在其他端口 |
| starshield-backend | 8080 | 启动时注入 `STARSHIELD_AI_LIGHTWEIGHT_URL=http://127.0.0.1:5050/score` |
| DeepSeek API Key | — | 通过仓库根目录 `.env` 文件注入 `DEEPSEEK_API_KEY=...`（已在 `.gitignore` 中） |

> macOS 用户提示：System Settings → General → AirDrop & Handoff → 关闭 AirPlay Receiver，可释放 5000 端口。否则需要把 ai-service 跑到其他端口（建议 5050）。

---

## 2. 测试样本与判定矩阵

| pid | 内容 | 期望路径 |
|---|---|---|
| NORMAL | 今天游戏体验不错 | A=PASS, B=lightweight PASS（<0.3） |
| EXACT | 傻逼 | A 命中"傻逼"，B 走中段 → LLM |
| LONG | 你这个代充加V的傻逼 | A 应命中3词→BLOCK 跳过 B（设计上） |
| AIBLK | 你妈死了 | A 应命中"你妈"→REVIEW，B 高分 BLOCK |
| MID | 求带飞 麻烦了 | A=PASS, B 中段 → LLM |
| LIGHT | 全家死光 | A=PASS, B 中段 → LLM |
| LLM3 | 全价四万 | 谐音"全家死完"，需 LLM Prompt 知识 |
| LLM4 | 你马四了 | 谐音"你妈死了"，B 高分 BLOCK |
| LLM5 | 今天卡得我想骂人 | 不是真骂人，B 中段 → LLM 应判 PASS |

---

## 3. 测试三轮的关键观察

### Round 1（首发）：发现 BUG#1（INSERT 全失败）

发了 6 条样本，DLQ 立即增长到 3106 条（含历史压测堆积）。日志显示：

```
ERROR --- ChatMessageConsumer : [消息消费] 处理失败 deliveryTag=1
java.sql.SQLIntegrityConstraintViolationException: Column 'create_time' cannot be null
```

`ChatMessageLog.createTime` 字段标了 `@TableField(fill = FieldFill.INSERT)`，但项目里**没有任何 `MetaObjectHandler` 实现类**。MyBatis-Plus 在没有 handler 的情况下不会自动填充，导致每条消息处理完调 `chatMessageService.save()` 都因 `create_time = null` 触发约束错误，全部 NACK 进 DLQ。

**影响范围**：`/api/dashboard/metrics`、`/api/admin/moderation`、`/api/archive/search` 看不到任何新消息（看到的全是 `seed_chat_message_1000.sql` 种子数据）。

### Round 2（修 BUG#1 后）：链路打通，但 BUG#2 露头

新增 `com.starshield.backend.config.MyBatisMetaObjectHandler` 后，6 条样本全部成功落库（DLQ 维持 0）。从结果里看到：

| pid | 内容 | A.hitWords | 设计期望 | 实测 | 偏差 |
|---|---|---|---|---|---|
| NORMAL | 今天游戏体验不错 | `[]` | A=PASS | A=PASS ✓ | — |
| EXACT | 傻逼 | `[傻逼]` | A=REVIEW(70) | A=REVIEW(70) ✓ | — |
| LONG | 你这个代充加V的傻逼 | **`[]`** | A 应命中3词 BLOCK 跳B | A=PASS，靠 B 救场 BLOCK | ⚠️ A 漏过 |
| AIBLK | 你妈死了 | **`[]`** | A 应命中"你妈" REVIEW | A=PASS，靠 B 救场 BLOCK | ⚠️ A 漏过 |
| MID | 求带飞 麻烦了 | `[]` | A=PASS | A=PASS ✓ | — |
| LIGHT | 全家死光 | `[]` | A=PASS | A=PASS ✓ | — |

`LONG` 和 `AIBLK` 在 Redis 词表里**有**对应敏感词，引擎 A 却没看到 → 这就是 BUG#2 的现场。

### Round 3（配上 DeepSeek KEY）：LLM 真实调用 + 合并验证

8 条样本中 5 条命中中段分（0.3 ≤ score < 0.8），全部成功调用 DeepSeek，返回结果 `modelTier=llm`：

| pid | 内容 | A.hits | AI tier | AI decision | LLM reason | 最终 |
|---|---|---|---|---|---|---|
| NORMAL | 今天游戏体验不错 | `[]` | lightweight | PASS | — | PASS |
| EXACT | 傻逼 | `[傻逼]` | **llm** | BLOCK | 直接使用侮辱性词汇'傻逼'，属于明确辱骂行为 | BLOCK |
| LLM1 | 全家死光 | `[]` | **llm** | BLOCK 98 | 包含明确诅咒内容'全家死光'，属于严重人身攻击和威胁 | BLOCK |
| LLM2 | 求带飞 麻烦了 | `[]` | **llm** | PASS | 正常游戏请求，无违规内容 | PASS |
| LLM3 | **全价四万** | `[]` | **llm** | BLOCK | **全价四万是全家死完的谐音变体，属于诅咒类违规内容** | BLOCK |
| LLM4 | 你马四了 | `[]` | lightweight | BLOCK | — (轻量 0.86) | BLOCK |
| LLM5 | 今天卡得我想骂人 | `[]` | **llm** | PASS | 表达游戏体验中的正常情绪，没有具体辱骂或攻击性内容 | PASS |
| LIGHT | 你这个代充加V的傻逼 | `[]` ⚠️ | lightweight | BLOCK | — (轻量 0.93) | BLOCK |

**亮点**：

- 谐音变体识别（LLM3「全价四万」）—— 正是 `ControlPanelService.DEFAULT_PROMPT_V2` 里写进去的知识，证明 **Prompt 经 Redis/默认值正确传给了 LLM**。
- 三档分流准确：`<0.3` → lightweight PASS（短路）；`≥0.8` → lightweight BLOCK（短路）；中段 → 真 LLM。
- 合并规则正确：EXACT「傻逼」A=REVIEW(70) + LLM=BLOCK(95) → 取大 risk + 任一 BLOCK → 最终 BLOCK 95。

---

## 4. 发现的 BUG

### 🔴 BUG#1（已修复）：`MetaObjectHandler` 缺失导致消息全部 NACK 进 DLQ

- **症状**：所有 INSERT chat_message_log 因 `create_time` 为空触发完整性约束，消费者吞掉全部消息。新消息从来不曾真正落库。
- **修复**：新增 `starshield-backend/src/main/java/com/starshield/backend/config/MyBatisMetaObjectHandler.java`：

```java
@Component
public class MyBatisMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        if (metaObject.hasGetter("createTime")) {
            strictInsertFill(metaObject, "createTime", LocalDateTime.class, now);
        }
        if (metaObject.hasGetter("updateTime")) {
            strictInsertFill(metaObject, "updateTime", LocalDateTime.class, now);
        }
    }
    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasGetter("updateTime")) {
            strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        }
    }
}
```

- **验证**：Round 2 / Round 3 共 14 条样本全部落库，DLQ 维持 0。

### 🟠 BUG#2（待修复）：BloomFilter 用法反了 —— 引擎 A 几乎全失效

由 PR#2（P4 分支）引入 `RuleEngineService` 的 BloomFilter 优化时用法反了。

```java
// 当前 RuleEngineService.fastCheck（错）
if (bloomFilter != null && !bloomFilter.mightContain(normalized)) {
    return PASS;     // 用整段消息查 BF
}
```

BF 里 `put` 的是**敏感词本身**（"傻逼"、"代充" 等），所以 `mightContain(normalized)` 在问的是「用户的整段消息是否恰好等于某个敏感词」。这意味着只有"消息=词典词"时才进精确匹配，其余消息全被 PASS 漏过。

**实测证据（Round 2/3）**：

| 消息 | 是否=词典词 | A.hitWords | 是否漏过 |
|---|---|---|---|
| 傻逼 | ✅ | `[傻逼]` | 否 |
| 你这个代充加V的傻逼 | ❌ | `[]` | **是** |
| 你妈死了 | ❌ | `[]` | **是** |

最终决策只所以仍然正确，是因为引擎 B（轻量/LLM）救场。如果 B 异常或被绕过，引擎 A 等于摆设。

#### 建议修复方案

最小改动：**移除 BF 的快速 PASS 分支**（让所有消息都经精确匹配；BF 不再起加速作用，回到 PR#2 之前的行为）：

```java
public FastCheckResult fastCheck(String content) {
    String normalized = normalize(content);
    List<String> hitWords = new ArrayList<>();
    for (String word : loadSensitiveWords()) {
        if (!word.isBlank() && normalized.contains(word)) {
            hitWords.add(word);
        }
    }
    // ... 原逻辑
}
```

正确做法（保留 BF 加速）：把消息切成长度 ∈ [min(len(word)), max(len(word))] 的滑窗子串，对每个子串 `mightContain`，命中再走精确匹配确认。这样 BF 才能真正起到「绝大多数干净消息直接放过、少数候选再精确匹配」的提速作用。

### 🟡 BUG#3 / 改进点：LLM 路径 `riskScore` 与 `decision` 语义错位

`AiAnalysisService.parseDeepSeekResponse` 把 LLM 的 `confidence` 直接当作 `riskScore`：

```java
double confidence = clamp(llmJson.path("confidence").asDouble(fallbackScore), 0d, 1d);
...
.setRiskScore(toRiskScore(confidence))
```

但 LLM 的 `confidence` 是「对自己判断的信心」，不是「违规风险」。实测：

| 内容 | LLM decision | LLM confidence | 数据库 riskScore |
|---|---|---|---|
| 求带飞 麻烦了 | PASS | 0.95 | **95**（看着是高风险，实际是 PASS） |
| 今天卡得我想骂人 | PASS | 0.95 | **95** |

不影响决策对错，但大屏排序、`/api/admin/moderation` 按风险分降序时会把这些 LLM 高自信度的 PASS 消息排到前面，体验奇怪。

**建议**：

```java
int risk = switch (decision) {
    case "PASS"   -> (int) Math.round((1 - confidence) * 100);  // confidence 越高 risk 越低
    case "BLOCK"  -> (int) Math.round(confidence * 100);
    case "REVIEW" -> 50;  // 或者 (int) Math.round(confidence * 50 + 25);
    default -> 50;
};
```

---

## 5. 复现 / 重跑步骤

```bash
# 1. 准备
cd /path/to/大规模试验
echo 'DEEPSEEK_API_KEY=<your-key>' > .env   # .gitignore 已排除

# 2. 起 ai-service（venv 跑 5050）
python3 -m venv ai-service/.venv
ai-service/.venv/bin/pip install -r ai-service/requirements.txt
PORT=5050 ai-service/.venv/bin/python ai-service/serve.py &

# 3. 起 backend（注入轻量 URL；KEY 自动从 ../​.env 读）
cd starshield-backend
STARSHIELD_AI_LIGHTWEIGHT_URL=http://127.0.0.1:5050/score mvn spring-boot:run

# 4. 写入测试敏感词
curl -X PUT http://127.0.0.1:8080/api/control/rules/sensitive-words \
     -H 'Content-Type: application/json' \
     -d '{"words":["傻逼","代充","加V","点击链接","色情","你妈","死全家"]}'

# 5. 发样本
for content in "今天游戏体验不错" "傻逼" "你这个代充加V的傻逼" "你妈死了" \
               "求带飞 麻烦了" "全家死光" "全价四万" "你马四了" "今天卡得我想骂人"; do
  curl -X POST http://127.0.0.1:8080/api/chat/upload \
       -H 'Content-Type: application/json' \
       -d "{\"playerId\":\"E2E_$(date +%s%N)\",\"content\":\"$content\",\"platform\":\"GAME_INNER\"}"
  sleep 2
done

# 6. 等待消费、查看
sleep 30
curl http://127.0.0.1:8080/api/dashboard/metrics | jq '.data.latest[:10]'
```

---

## 6. 后续 TODO

- [ ] 合并 BUG#1 修复：`MyBatisMetaObjectHandler.java`
- [ ] 跟 PR#2 作者沟通 BUG#2（BloomFilter）修复方向
- [ ] BUG#3：LLM 路径 riskScore 语义重映射（小改动）
- [ ] 沉淀为 `scripts/e2e_redis_model.sh`，并在 CI 上跑（可选）
- [ ] 给 `RuleEngineService` 补充**消息含敏感词但非完全等于**的单元测试（之前测试都用恰好等于敏感词的样本，掩盖了 BUG#2）

---

## 附：关键路径源码索引

- `controller/ChatMessageController.java` — `POST /api/chat/upload`，限流后投 MQ
- `consumer/ChatMessageConsumer.java` — 消费、串联 A→B、合并、落库
- `service/RuleEngineService.java` — 引擎 A，含 Redis 敏感词热加载与 BloomFilter（含 BUG#2）
- `service/AiAnalysisService.java` — 引擎 B，轻量 + DeepSeek，含 dotenv 回退（含 BUG#3）
- `service/ControlPanelService.java` — `/api/control/*` 的服务端，含默认 Prompt V2
- `config/RabbitMQConfig.java` — 交换机、队列、DLQ 拓扑
- `config/MyBatisMetaObjectHandler.java` — 本次新增（BUG#1 修复）
