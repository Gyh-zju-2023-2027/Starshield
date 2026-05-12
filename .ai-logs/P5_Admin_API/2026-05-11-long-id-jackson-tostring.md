## 阶段日志（增量）
- **日期**：2026-05-11
- **角色**：P5_Admin_API
- **主题**：Long → String 全局 JSON 序列化，根治雪花 ID 跨 JS 边界丢精度

## 1. 核心提示词 (Prompt)
"审核后台点封禁/解除无任何反馈。P10 已定位为 row.id（雪花 Long 19 位）传到 JS Number 后被四舍五入，后端按错 ID 查 404。请在不改 entity / DTO 的前提下，全局把 Long 序列化为 String，且不能影响 LocalDateTime 等其他类型的序列化。"

## 本次新增
1. 新增 `com.starshield.backend.config.JacksonConfig`：
   - 注册 `Jackson2ObjectMapperBuilderCustomizer`，通过 `SimpleModule` 把 `Long.class / Long.TYPE / BigInteger.class` 全部绑定到 `ToStringSerializer.instance`。
   - **关键细节**：使用 `builder.modulesToInstall(module)` 而非 `builder.modules(module)`。
     - `modules(...)` 会**替换** Spring Boot 默认装的所有模块（含 `jackson-datatype-jsr310`、`jdk8`、`parameter-names`），导致 `LocalDateTime` 等类型立即抛 `InvalidDefinitionException`。
     - `modulesToInstall(...)` 是**追加**，原模块全部保留。
2. 验证：
   - `GET /api/dashboard/metrics` 返回的 `latest[].id` 字段从 `2053405083167641600`（错） 变为 `"2053405083167641601"`（正）。
   - `POST /api/admin/moderation/{id}/confirm-ban` 用前端 String ID 调用，`@PathVariable Long id` 仍能正常反序列化（Spring 自动 String → Long）。
   - `LocalDateTime` 字段（`createTime` 等）继续按 ISO 字符串输出，无回归。

## 价值
- 一次性根治所有 Long 类型 ID 跨边界场景（审核、大屏 latest、审计日志……），不需要逐字段加 `@JsonSerialize(using = ToStringSerializer.class)`。
- 后续新增任何含 Long ID 的 entity 自动受益，零额外成本。

## 教训
- Spring Boot Jackson 的 `modules(...)` 是"replace all"语义，文档不显眼但坑很深；任何自定义模块**必须**用 `modulesToInstall(...)`。
- 这次首次提交直接让所有带时间字段的接口全 500，前端"一切都没数据"。修复时间约 3 分钟（看异常栈第一行即定位），但已记录为团队踩坑案例。

## 待执行
- 在 `docs/api-change-log.md` 追加一条说明："所有响应中的 Long 类型字段（含雪花 ID）以 JSON 字符串形式返回，前端按 String 处理"。
- 评估是否需要把同样规则下沉到通用 `Result<T>` 包装层，使 API 契约在文档级别显式。
