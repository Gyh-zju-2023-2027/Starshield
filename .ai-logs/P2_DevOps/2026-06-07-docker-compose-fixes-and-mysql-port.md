## 阶段日志
- **日期**：2026-06-07
- **角色**：P2_DevOps
- **主题**：Docker 全栈启动修复（toolchain / 端口 / 健康检查 / 模式装配）

## 1. 核心提示词 (Prompt)
"docker compose up 失败：镜像源 EOF、Maven toolchain 缺失、3306 端口冲突、ingest/api 容器启动后 gateway 无法就绪；请修复并跑通全栈。"

## 2. 变更说明 (Modifications)

| 文件 | 变更 |
|------|------|
| `starshield-backend/Dockerfile` | 构建阶段生成 `~/.m2/toolchains.xml`，指向镜像内 `$JAVA_HOME`（JDK 17） |
| `docker-compose.yml` | MySQL 宿主机映射 `3306→3307`，避免与 Homebrew MySQL 冲突 |
| `docker-compose.yml` | 健康检查改为 `/actuator/health/liveness`（ingest 无 DB 时 aggregate health 为 503） |
| `CrawlTaskServiceImpl` / `CrawlTaskController` | 增加 `@EnabledOnMode(MONOLITH, API)`，ingest 模式不再加载爬取任务 |
| `RuleEngineService` / `AiAnalysisService` / `ArchiveSyncService` | API 模式补充 Bean 装配，修复 `ControlPanelService` / `DailyReportService` 依赖链 |

## 3. 踩坑记录

1. **Docker 镜像加速器**：`docker.mirrors.ustc.edu.cn` 返回 EOF → 需在 Docker Desktop 更换或移除 registry-mirrors。
2. **Maven toolchains**：容器内无 `~/.m2/toolchains.xml`，`mvn package` 报 `jdk [ version='17' ]` 找不到。
3. **双 MySQL 实例**：本机 `mysqld:3306` 与 Docker `mysql_data` 卷互不相通；用户误以为「丢数据」，实为读错库。
4. **健康检查**：ingest 排除 DataSource 后 `/actuator/health` 返回 503，`curl -sf` 失败 → gateway 无法启动。

## 4. 验证

```bash
docker compose up -d --build
curl -s http://127.0.0.1:8080/api/dashboard/metrics   # code=200
docker ps | grep starshield                              # ingest/api/worker/gateway healthy
```

## 5. 待增强

- [ ] `docker-compose` 增加可选 profile：仅中间件 / 全栈，文档说明与本机 MySQL 共存策略
- [ ] ingest 容器内 CrawlTask 不应依赖 Python 宿主机路径（当前 API 模式才支持控制台拉起脚本）
- [ ] 数据迁移脚本：本机 3306 → Docker 3307 一键 `mysqldump`
