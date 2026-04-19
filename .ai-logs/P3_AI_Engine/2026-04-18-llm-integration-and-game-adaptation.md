## 阶段日志
- **日期**：2026-04-18
- **角色**：P3_AI_Engine
- **任务**：完成轻量模型 + DeepSeek 精判链路落地，增强游戏语境与谐音识别能力

## 1. 核心提示词 (Prompt)
"实现 P3 双层判定：先走本地轻量模型分流（PASS/BLOCK 快速判定），中间区间走 DeepSeek 精判；支持降级与可观测字段，补齐模块测试，并针对游戏谐音攻击优化 Prompt。"

## 已完成
1. AI 主链路改造（Java）：
   - `AiAnalysisService` 接入本地 Flask `/score` + DeepSeek `/chat/completions`。
   - 分流策略：`score>=0.8 -> BLOCK`，`score<0.3 -> PASS`，其余走 LLM。
   - 降级策略：轻量模型失败默认 `0.5`；LLM失败返回 `REVIEW + degraded=true`。
   - 增加 `.env` 回退读取 `DEEPSEEK_API_KEY`（优先环境变量）。
2. 统一结果模型升级：
   - `AiModerationResult` 补齐 `modelTier`、`degraded`，并统一 `riskScore` 为 `int`。
3. Prompt 版本化与语境增强：
   - `ControlPanelService` 支持按 `promptVersion` 读取 Redis Prompt（含兼容 key）。
   - 默认 Prompt 升级为游戏平台审核专家语境，加入“全价四万=全家死完”等谐音规则。
   - `application.yml` 更新 `starshield.ai.prompt-version: v2`。
4. 轻量模型训练与服务（Python）：
   - 新增 `train.py`（TF-IDF + LR），支持中文字符 n-gram 与拼音增强。
   - 新增 `serve.py`，提供 `/health`、`/score`。
   - 新增 `build_game_dataset.py`、`merge_training_data.py`，用于游戏语料构建与合并训练集。
   - 基于 COLD + 游戏增强数据重训，AUC 提升至约 `0.98`。
5. 测试补齐：
   - `AiAnalysisServiceTest`：分流/降级/JSON容错等单测通过。
   - `AiAnalysisServiceIntegrationTest`：真实 LLM 路径验证（并打印结果）。
   - 在网络可用情况下，`你全价四万了` 已可返回 `BLOCK + abuse + llm + degraded=false`。

## 变更文件
- `starshield-backend/src/main/java/com/starshield/backend/model/AiModerationResult.java`
- `starshield-backend/src/main/java/com/starshield/backend/service/AiAnalysisService.java`
- `starshield-backend/src/main/java/com/starshield/backend/service/ControlPanelService.java`
- `starshield-backend/src/main/resources/application.yml`
- `starshield-backend/src/test/java/com/starshield/backend/service/AiAnalysisServiceTest.java`
- `starshield-backend/src/test/java/com/starshield/backend/service/AiAnalysisServiceIntegrationTest.java`
- `ai-service/train.py`
- `ai-service/serve.py`
- `ai-service/build_game_dataset.py`
- `ai-service/merge_training_data.py`
- `ai-service/requirements.txt`

## 风险与备注
- 网络出口/证书环境会影响 DeepSeek 直连，需在可直连网络下做最终联调验收。
- `.env` 已加入本地 key，但仓库 `.gitignore` 已忽略，避免泄漏。

## 下一步
- 增加固定评测集（谐音/缩写/边界语气）并做版本回归对比。
- 与 P7 对接消费链路联调，验证真实入队后端到端效果。
