## 阶段日志
- **日期**：2026-04-06
- **角色**：P8_Admin_FE
- **任务**：管理后台前端（人工复核）

## 已完成
1. 新增审核后台页面 `AdminReview.vue`：
   - 待审核列表
   - 风险分/标签/内容展示
   - 操作按钮：确认封禁 / 解除
2. 新增后台 API 客户端 `src/api/admin.js`。
3. 在 `App.vue` 增加多页签入口，支持直接切换至审核后台。

## 变更文件
- `starshield-frontend/src/views/AdminReview.vue`
- `starshield-frontend/src/api/admin.js`
- `starshield-frontend/src/App.vue`

## 下一步
- 增加详情抽屉（模型理由、命中词、历史操作）
- 增加批量审核、二次确认与失败回滚。
