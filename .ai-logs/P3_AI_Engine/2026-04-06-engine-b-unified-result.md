## 阶段日志
- **日期**：2026-04-06
- **角色**：P3_AI_Engine
- **任务**：引擎 B（深度语义）从占位实现升级为统一结果模型

## 已完成
1. 新增 `AiModerationResult` 模型，统一字段：
   - `riskScore`
   - `labels`
   - `decision`（PASS/REVIEW/BLOCK）
   - `confidence`
   - `reason`
   - `provider`
   - `promptVersion`
2. 重写 `AiAnalysisService`：
   - 支持配置化 provider/promptVersion
   - 输出统一结构结果
   - 预置语义策略（辱骂/引流/阴阳）
3. 在消费者中完成引擎 A 与引擎 B 的融合判定（取高风险分 + 决策合并）。

## 变更文件
- `starshield-backend/src/main/java/com/starshield/backend/model/AiModerationResult.java`
- `starshield-backend/src/main/java/com/starshield/backend/service/AiAnalysisService.java`
- `starshield-backend/src/main/java/com/starshield/backend/consumer/ChatMessageConsumer.java`
- `starshield-backend/src/main/resources/application.yml`

## 下一步
- 接入真实 LLM API（DeepSeek/Qwen）并替换当前规则化 mock 实现。
- 增加 prompt 版本灰度与回滚接口。
