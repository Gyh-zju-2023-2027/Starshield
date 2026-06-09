## 阶段日志
- **日期**：2026-06-07
- **角色**：P10_QA_Test
- **主题**：多平台数据接入（IdentityV-weibo + B 站直播实时弹幕）与 Docker 联调验证

## 1. 核心提示词 (Prompt)
"用 IdentityV-weibo 数据集按 chat_message_log 格式入库；实现 B 站直播间 WebSocket 实时弹幕；Docker 跑通后在前端验证。"

## 2. 测试范围

| 模块 | 脚本/入口 | 验证点 |
|------|-----------|--------|
| 微博数据集 | `bilichat-ingest/ingest_identityv_weibo.py` | HF 下载 → JSONL → `platform=WEIBO` 推送 |
| B 站视频评论 | `bilichat-ingest/ingest_comments.py --type video` | 既有链路回归 |
| B 站直播实时 | `bilichat-ingest/bili_live_realtime.py` + `--live-mode realtime` | WSS 认证、DANMU_MSG 解析、即时 push |
| 后端 upload | `POST /api/chat/upload` | 可选 `createTime` 透传至 MQ/落库 |
| 前端 | `CrawlConsole.vue` | 任务类型：视频 / 直播(实时) / 第五人格微博 |
| Docker | `docker compose` + gateway :8080 | ingest 接入 + worker 消费 + api 大屏 |

## 3. 执行结果摘要

### 3.1 IdentityV-weibo

- HuggingFace 数据集 `weibo_dataset.jsonl`（~17MB）下载成功。
- 导出 JSONL 字段对齐：`player_id` / `text` / `ctime` / `platform=WEIBO`。
- 曾出现 `Connection refused :8080`：后端未启动导致推送失败（非脚本逻辑问题）。
- **建议复测命令**：
  ```bash
  cd bilichat-ingest
  .venv/bin/python ingest_identityv_weibo.py --skip-export --out-jsonl data/identityv_weibo.jsonl --rps 20
  ```

### 3.2 B 站直播实时弹幕

- 协议：room_init → getDanmuInfo → WSS `/sub` → op7 认证 / op2 心跳 / op5 DANMU_MSG。
- 修复：按 `operation` 解析帧（原误用 `cmd=LOGIN` JSON）。
- 限制：需直播间**正在开播**；`gethistory` 快照模式仍保留作 fallback（`--live-mode history`）。

### 3.3 Docker 全栈（2026-06-07 修复后）

| 检查项 | 结果 |
|--------|------|
| `GET /api/dashboard/metrics` | ✅ code=200 |
| ingest / api / worker 容器 | ✅ healthy |
| gateway :8080 | ✅ Up |
| Docker MySQL `chat_message_log` | BILIBILI 11762 条（含历史迁移/导入）；与本机 3306 独立 |

### 3.4 前端

- 启动：`cd starshield-frontend && npm run dev` → http://localhost:5173
- Vite 代理 `/api` → `localhost:8080`（Docker gateway）

## 4. 已知问题 / 风险

| 级别 | 问题 | 说明 |
|------|------|------|
| HIGH | 双 MySQL 数据隔离 | Docker 卷 vs Homebrew 3306，易误判「数据丢失」 |
| MEDIUM | 直播任务依赖开播状态 | 关播房间 `total=0`，控制台报 error 事件 |
| MEDIUM | CrawlTask 控制台在 Docker API 模式拉起 Python | 容器内无 `bilichat-ingest`，需宿主机路径或 sidecar |
| LOW | WEIBO 导入未做 HTML 实体全量反转义 | 当前 strip 标签足够 POC |

## 5. 待执行

- [ ] WEIBO 5000 条推送完成后统计 `decision` 分布，与 BILIBILI 样本对比
- [ ] 选 1 个正在直播的房间做 `--live-mode realtime --target-count 100` 端到端
- [ ] Docker 环境下重跑 `secure-test`（鉴权 FAIL 项仍为 P0）
