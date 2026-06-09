# 2026-06-09 前端路由级拆包

## 背景

当前 `App.vue` 静态 import 所有视图，生产构建会把多页面业务代码一起打进首屏依赖。随着控制台、日报、大屏、封禁分析继续增长，首屏 JS 会越来越重。

## 改动

| 文件 | 说明 |
|---|---|
| `src/App.vue` | 手写 tab 切换升级为路径路由；每个视图用 `defineAsyncComponent(() => import(...))` 懒加载 |
| `src/main.js` | 去掉全量 Element Plus / Icons 注册，按实际使用组件注册 |
| `src/utils/echartsCore.js` | ECharts 改为 core 按需注册 bar / line / pie 与基础组件 |
| `src/views/DailyReport.vue` | `html2pdf.js` 改为点击导出时动态加载 |
| `vite.config.js` | 保留 Vue / PDF 稳定 chunk，避免业务页面被 vendor 混入 |

## 结果

- 支持 `/test`、`/crawl`、`/admin`、`/dashboard`、`/ban`、`/control`、`/report` 路径。
- 首屏只加载当前视图，其他视图进入时再拉取对应 chunk。
- 菜单点击会同步 `history.pushState`，浏览器前进/后退能恢复当前视图。
- `npm run build` 通过；页面业务 chunk 约 5-14KB，Element 相关 JS 约 446KB，ECharts 按需 chunk 约 522KB，PDF chunk 仅导出时加载。

## 后续

- 若后续增加独立前端 Nginx，需要配置 history fallback 到 `index.html`。
- 如果路由权限复杂化，再引入 `vue-router` 会更合适。
