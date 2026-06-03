## 阶段日志
- **日期**：2026-06-02
- **角色**：P2_DevOps
- **任务**：Docker 微服务化拆分（降低接入延迟）

## 1. 核心提示词 (Prompt)
"我想实现 docker 微服务化减小延时——将单体后端拆为接入 / 消费 / 运营 API 三进程，配套 docker-compose 与 Nginx 网关，本地开发仍保持 monolith 模式。"

## 2. 变更说明 (Modifications)
- 新增运行时模式：`starshield.runtime.mode` = `monolith` | `ingest` | `worker` | `api`
- 新增 `@EnabledOnMode` 条件装配，按模式加载 Controller / Consumer / Service
- 新增 Docker 配置：
  - `starshield-backend/Dockerfile`
  - `ai-service/Dockerfile`
  - `docker-compose.yml`
  - `docker/nginx/default.conf`（upload → ingest，其余 → api）
- 新增 Spring Profile：`application-docker*.yml`（ingest / worker / api）
- 新增 `spring-boot-starter-actuator` 供容器健康检查
- 文档：`docs/docker-microservices.md`

## 3. AI 决策依据
- **同一 JAR 多 Profile**：避免拆 Maven 模块，本地 `mvn spring-boot:run` 零改动
- **ingest 排除 DataSource/Redis/MyBatis 自动配置**：接入 JVM 更轻，Tomcat 线程专用于 upload
- **Nginx 网关统一 8080**：与现有前端 Vite 代理、压测脚本 host 保持一致
- **Worker 内网调用 ai-service**：容器 bridge 网络降低轻量模型 RTT

## 4. 部署要点
```bash
docker compose up -d --build
docker compose up -d --scale starshield-worker=2 --scale starshield-ingest=2
```

## 5. 待增强
- [ ] 管理 / 控制面 / reindex 鉴权（与安全测试 FAIL 项联动）
- [ ] Prometheus 指标导出（接入 QPS、MQ 深度、消费延迟）
- [ ] K8s Helm Chart（由 compose 迁移）

## 6. 相关 Commit
`2b96422` feat: Docker 微服务拆分、安全测试与压测报告
