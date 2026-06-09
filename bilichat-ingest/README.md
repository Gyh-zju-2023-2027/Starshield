# bilichat-ingest

把 B 站真实评论爬到本地，再灌入 StarShield 后端，让消息走完
`MQ → Consumer → 引擎A(Bloom+敏感词) → 引擎B(轻量模型/DeepSeek) → 落库 → 大屏`
全链路。

> **目标场景**：1w+ 真实评论，验证布隆过滤 + 模型判断在大规模真实数据下的表现。

---

## 0. 前置条件

后端三件套要先起来：

```bash
# 1) Redis / RabbitMQ / MySQL（按 DEPLOY.md / e2e-redis-model-report.md）
# 2) ai-service（轻量模型 /score）
PORT=5050 ai-service/.venv/bin/python ai-service/serve.py &

# 3) starshield-backend（注入 5050）
cd starshield-backend
STARSHIELD_AI_LIGHTWEIGHT_URL=http://127.0.0.1:5050/score mvn spring-boot:run
```

> ✅ **规则引擎说明**：`RuleEngineService` 的 BloomFilter 漏审问题已在 2026-06-08 修复；导入真实评论后，引擎 A 会先做安全候选排除，再执行敏感词精确匹配。

可选：先把敏感词写进 Redis（不写也有内置 5 个默认词）：

```bash
curl -X PUT http://127.0.0.1:8080/api/control/rules/sensitive-words \
     -H 'Content-Type: application/json' \
     -d '{"words":["傻逼","代充","加V","点击链接","色情","你妈","死全家","脑残","废物","菜狗"]}'
```

---

## 1. 安装依赖

```bash
cd bilichat-ingest
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

---

## 2. 准备 BV 号清单

打开 [B 站热门](https://www.bilibili.com/v/popular/all/) 抄一批评论数 5w+ 的视频
BV 号到 `bvids.txt`（已附示例，但可能时效性不够，**建议替换**）。

要凑 1w 条，一般 5–10 个高热度 BV 就够了。

---

## 3. 跑！

### 一条命令搞定（边爬边推）

```bash
.venv/bin/python ingest_comments.py \
    --bvid-file bvids.txt \
    --target-count 12000 \
    --rps 50 --workers 16 \
    -v --log-file run.log
```

* `--target-count 12000`：累计抓到 1.2 万条停止（预留缓冲，避免重复去重后不够 1w）
* `--rps 50`：推送速率 50 QPS，远低于后端 IP 限流 300/s，安全
* `--workers 16`：推送并发线程数

### 分两步（推荐：先看一眼数据再灌）

```bash
# 步骤 A：只爬不推，先把数据落到本地 JSONL
.venv/bin/python ingest_comments.py \
    --bvid-file bvids.txt --target-count 12000 \
    --skip-push -v

# 看眼前几条
head -3 data/bili_comments.jsonl

# 步骤 B：再把本地 JSONL 推到后端
.venv/bin/python ingest_comments.py \
    --skip-fetch \
    --out-jsonl data/bili_comments.jsonl \
    --rps 50 --workers 16 -v
```

### 想跑得更快？

* 后端 `application.yml` 把 `starshield.rate-limit.ip-qps` 调大到 1000+
* `--rps` 调到 200+，`--workers` 32+

---

## 4. 验证链路效果

```bash
.venv/bin/python verify_pipeline.py --watch
```

输出形如：

```
全局总量: 12345
BLOCK   : 287
REVIEW  : 412
阻断率  : 2.32%
最近 100 条 BILIBILI 样本:
  PASS   :   83  (83.00%)
  REVIEW :   10  (10.00%)
  BLOCK  :    7  ( 7.00%)

--- 抽样 BLOCK ---
  [risk= 95] hits=傻逼,废物 labels=keyword_violation
     ……
```

如果 `BLOCK + REVIEW` 占比异常低（<1%），多半是引擎 A 的 BloomFilter
还没修，可参考 `docs/e2e-redis-model-report.md` BUG#2。

---

## 5. 文件说明

| 文件 | 作用 |
|---|---|
| `ingest_comments.py` | 主脚本：B 站爬 → 本地 JSONL → 推后端 |
| `verify_pipeline.py` | 拉 `/api/dashboard/metrics` 看决策分布 |
| `bvids.txt` | BV 号清单（请按需替换为当下评论较多的视频） |
| `requirements.txt` | 仅依赖 `requests` |
| `data/bili_comments.jsonl` | 本地落盘的原始评论（运行时生成） |

---

## 6. 常见踩坑

| 现象 | 原因 / 处理 |
|---|---|
| 抓到 0 条 | BV 号已下架/评论关闭；换 BV 号；或加 `--cookie 'SESSDATA=...'` 提升上限 |
| 推送大量 429 | 把 `--rps` 调小，或在 `application.yml` 里调高 `ip-qps` |
| 大屏 total 不涨 | 检查 RabbitMQ DLQ：消费者侧多半挂了；看 `BUG#1` 修复是否生效 |
| 评论里有大量 emoji/at/链接 | 已在 `extract_reply_text` 取 `content.message` 纯文本，自动清掉富结构 |
