## 阶段日志（增量）
- **日期**：2026-05-11
- **角色**：P8_Admin_FE
- **主题**：审核后台操作按钮"静默失败"防御性兜底

## 1. 核心提示词 (Prompt)
"审核后台点封禁/解除完全没反应，根因是后端返回 code=404，但前端只判断了 code===200，没有 else 也没有 try/catch。在 P5 后端已经把 Long ID 转 String 的前提下，把前端兜底也补上，让任何接口失败都能看到错误提示。"

## 本次新增
1. `starshield-frontend/src/views/AdminReview.vue`：
   - `onBan(row)` / `onRelease(row)` 增加：
     - `else` 分支：当 `res.code !== 200` 时弹 `ElMessage.error(res?.message || '操作失败 (code=xxx)')`。
     - `try / catch`：捕获 axios reject（网络错误 / 5xx 等），优先取 `e.response.data.message`，回退到 `e.message`，再回退到 `String(e)`。
   - 批量封禁 / 批量解除已有 `try/catch + 回滚` 逻辑，未改动。

## 价值
- 与 P5 的 `JacksonConfig` 协同后，单条 / 批量操作均能正常生效；即使将来后端再次返回非 200，UI 也会立刻给出可见反馈，避免"点了没反应"的体验黑洞。
- 同步建立了「**所有调用业务接口的前端 action 必须有 else 分支 + try/catch**」的隐式约定，后续 CR 时可作为审查点。

## 待执行
- 复盘其他 view（`ControlPanel.vue`、`TestMock.vue` 等）是否也存在"只判 200，错误静默"模式，统一补齐。
- 评估在 `src/api/http.js` 的 axios response interceptor 中加全局错误 toast，作为最后一道保险（注意避免与各 view 内的局部错误处理重复弹窗）。
