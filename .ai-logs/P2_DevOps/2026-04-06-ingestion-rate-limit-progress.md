## 阶段日志
- **日期**：2026-04-06
- **角色**：P2_DevOps
- **任务**：接入层限流能力补齐（应用内保护层）

## 已完成
1. 新增 `IngestionRateLimiterService`，提供三层固定窗口限流：
   - 全局限流 `global-qps`
   - IP 级限流 `ip-qps`
   - 玩家级限流 `player-qps`
2. 在 `ChatMessageController#upload` 接入限流判定，触发时返回 `429`。
3. 新增业务配置：
   - `starshield.rate-limit.global-qps`
   - `starshield.rate-limit.ip-qps`
   - `starshield.rate-limit.player-qps`

## 变更文件
- `starshield-backend/src/main/java/com/starshield/backend/service/IngestionRateLimiterService.java`
- `starshield-backend/src/main/java/com/starshield/backend/controller/ChatMessageController.java`
- `starshield-backend/src/main/resources/application.yml`

## 下一步
- 对接网关层真实限流（Nginx/Kong/Spring Cloud Gateway）并保持与应用内限流阈值一致。
