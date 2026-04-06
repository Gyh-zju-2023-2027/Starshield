## 阶段日志（增量）
- **日期**：2026-04-06
- **角色**：P8_Admin_FE
- **主题**：审核详情时间线接入

## 本次新增
1. `AdminReview.vue` 行点击可打开详情抽屉。
2. 详情抽屉新增“操作时间线”（`el-timeline`），展示：
   - operator
   - action
   - 决策变化（before -> after）
   - 风险分变化（before -> after）
3. 新增前端 API：`fetchAuditLogs(id, limit)`。
4. 单条操作成功后若抽屉开启，自动刷新当前记录时间线。

## 价值
- 审核可追溯性在前端可视化闭环。
