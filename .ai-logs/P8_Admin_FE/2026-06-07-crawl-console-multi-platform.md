## 阶段日志
- **日期**：2026-06-07
- **角色**：P8_Admin_FE
- **主题**：爬取控制台支持多平台任务类型

## 1. 核心提示词 (Prompt)
"爬取控制台增加第五人格微博数据集导入、B 站直播实时弹幕任务；平台标签随任务类型切换。"

## 2. 变更说明 (Modifications)

- `starshield-frontend/src/views/CrawlConsole.vue`
  - 任务类型：`video` | `live`（实时 WebSocket）| `weibo`（IdentityV-weibo）
  - `weibo` 模式无需填写目标，自动提交 `targets: ['IdentityV-weibo']`
  - 平台 Chip：`BILIBILI` / `BILIBILI_LIVE` / `WEIBO`
  - 任务详情/进度文案统一走 `taskTypeText()`

## 3. 使用说明

1. 后端 Docker gateway 或 monolith 监听 **8080**
2. `npm run dev` → http://localhost:5173
3. 爬取控制台 → 选择任务类型 → 设置目标条数 / RPS → 开始

## 4. 限制

- Docker **API 容器**内执行 CrawlTask 时，Python 脚本路径在宿主机；纯 Docker 部署下控制台「开始爬取」可能失败，CLI 脚本仍可用。
- 直播任务需填写**正在直播**的房间号。
