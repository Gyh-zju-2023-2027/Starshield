# P9 Dashboard增强记录（2026-04-14）

## 本次目标
- 完善“热点词云”模块
- 完善“分区对比图（平台分布）”模块

## 实现内容
- 文件：`starshield-frontend/src/views/DashboardBoard.vue`
- 新增“热点词云（最近100条）”面板
  - 基于 `latest` 消息内容进行分词统计
  - 过滤常见停用词
  - 输出 Top24 热词，按词频映射字号
- 新增“分区对比图（平台分布）”面板
  - 使用 ECharts 柱状图展示平台消息量对比
  - 数据来自 `latest` 中的 `platform` 字段统计
- 与现有刷新体系对齐
  - 轮询/WS 到达新数据后自动刷新词云与分区图
  - 页面销毁时对新增 ECharts 实例执行 `dispose()`

## 数据约定
- 优先使用后端返回字段（若后续后端补充）：
  - `hotWords`: `[{ word, count }]`
  - `platformDistribution`: `{ [platform]: count }`
- 当前若后端未提供，则前端自动从 `latest` 回退计算。
