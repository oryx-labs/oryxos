# Research: 管理后台本地管理员认证

本研究解决了计划阶段的认证、会话、CSRF、审计与模块边界问题；没有遗留 `NEEDS CLARIFICATION`。

## Decision 1: 使用 Spring Security 的服务端会话，不使用 JWT

- **Decision**: 在 `oryxos-web` 引入 `spring-boot-starter-security`，以 Spring Security 的 Servlet 过滤链、`HttpSession` 与安全上下文保护管理访问。登录成功后仅在服务端会话中保存管理员主体；30 分钟无活动后由会话超时使其失效。
- **Rationale**: 管理台和 API 同源、首期只有一个管理员，服务端会话能提供立即退出、会话固定攻击防护和后续身份提供者替换点，而无需实现令牌签发、刷新和撤销。Spring Security 的官方文档说明认证上下文可由 HTTP session 持久化，并支持替换存储方式，保留了未来扩展余地。
- **Alternatives considered**:
  - JWT：拒绝。需要签发、刷新、撤销与客户端保管策略，超出单管理员管理台范围。
  - 自定义 session/cookie：拒绝。会重复实现成熟框架已有的固定攻击、注销和上下文持久化能力。

## Decision 2: 初始管理员只由环境变量配置，密码使用编码后的值

- **Decision**: 增加认证配置属性，管理员账号与 `{bcrypt}` 等可识别编码格式的密码散列仅从环境变量读取。运行时使用 Spring `PasswordEncoder` 进行比对；不提供默认账号、默认密码、注册或密码找回。
- **Rationale**: 宪法要求敏感配置不进入配置文件、日志或提交历史。编码后密码能避免在运行配置中保留可直接使用的密码；显式配置也比自动生成默认管理员更可审计。
- **Alternatives considered**:
  - 在 `application.yml` 写入明文密码：拒绝，违反凭证管理约束。
  - 启动时随机生成默认密码：拒绝，易遗失或泄露，且不符合“无默认凭证”。
  - 在本期创建用户表和用户管理界面：拒绝，多管理员和账号生命周期明确不在范围内。

## Decision 3: 静态登录壳公开，管理 API 服务端统一受保护

- **Decision**: 保持 `/admin/**` 可加载 Vue 登录壳，但在其认证成功前不加载任何管理数据。除认证引导端点和健康检查外，所有 `/api/v1/**` 管理接口均要求已认证管理员；Swagger/OpenAPI 与非健康 Actuator 信息同样不匿名开放。
- **Rationale**: 现有 `/admin/**` 是 SPA 回落，静态资源本身不含运行数据；真正的安全边界必须在 API 过滤链，不能靠前端条件渲染。既有前端所有管理动作均走 `/api/v1/**`，统一策略可防止直接调用 API 绕过 UI。
- **Alternatives considered**:
  - 只隐藏导航或按钮：拒绝，直接请求 API 仍可执行管理操作。
  - 要求 `/admin/**` 先认证：拒绝，登录页面资源与 SPA 回落会形成引导死锁，且不会替代 API 鉴权。

## Decision 4: 会话 Cookie 与 CSRF 保护共同使用

- **Decision**: 会话 Cookie 设置 `HttpOnly`、`SameSite=Strict`，生产 HTTPS 部署启用 `Secure`；所有改变服务器状态的请求（包括登录、退出及既有管理写操作）必须携带 CSRF 令牌。前端先通过认证引导端点取得令牌，再用统一的同源请求封装发送约定头；登录后的会话变更会刷新令牌。
- **Rationale**: Cookie 会话会由浏览器自动携带，必须辅以请求验证值抵御跨站请求伪造。Spring Security 官方文档提供适用于 JavaScript 应用的 `CookieCsrfTokenRepository` 以及默认的请求头约定；会话持久化文档也明确自定义登录控制器需要显式保存安全上下文。
- **Alternatives considered**:
  - 关闭 CSRF：拒绝，Cookie 会话下会暴露所有管理写操作。
  - 把访问令牌交给浏览器脚本保管：拒绝，扩大令牌泄露面且未减少实现复杂度。

## Decision 5: 失败限制为进程内、线程安全的 15 分钟窗口；安全事件持久化

- **Decision**: 对每个提交的账号维护线程安全的失败计数：15 分钟内第五次失败即锁定 15 分钟，成功登录清除该账号的失败状态。未知账号与错误密码走相同的认证与响应路径。新增 `admin_auth_events` 表持久化成功、失败、临时限制、退出事件；事件不包含密码、散列、CSRF 值或 session cookie。
- **Rationale**: 当前是单进程、单管理员范围，进程内失败状态可精确满足窗口与恢复要求且不引入额外同步组件。安全审计属于可追溯能力，需遵守宪法的手工 DDL 与 SQLite 落库规则；审计失败不得静默伪造“已记录”的登录成功。
- **Alternatives considered**:
  - 每次失败永久锁定：拒绝，需要人工解锁与账号管理，不符合首期范围。
  - Redis/分布式限流：拒绝，新增基础设施与本期单实例范围不相称。
  - 只把安全事件写日志：拒绝，不能满足可查询与持久追溯。

## Decision 6: 通过核心审计接口保持模块依赖倒置

- **Decision**: `oryxos-core` 新增 `AdminAuthAuditStore` 与不可变的 `AdminAuthEvent` 值对象；`oryxos-storage` 提供 JPA/SQLite 实现；`oryxos-web` 的认证服务仅依赖核心接口。`OryxOsRuntime` 显式创建该实现并启用认证配置属性。
- **Rationale**: 这与现有 `LlmCallAuditor`/`JpaLlmCallAuditor` 模式一致，避免 `oryxos-web → oryxos-storage` 的直接依赖，并为将来发送到企业审计平台保留替换点。
- **Alternatives considered**:
  - 新增 `oryxos-auth` 模块：拒绝，首期代码量不足以证明新模块边界的收益。
  - Web Controller 直接访问 JPA Repository：拒绝，破坏存储隔离和未来替换能力。

## Decision 7: 前端在现有 SPA 顶层维护登录态和统一请求入口

- **Decision**: 在 `App.vue` 顶层以认证状态决定渲染登录卡片或既有管理布局；抽取 `api.js` 统一设置 `credentials: 'same-origin'`、CSRF 头、`ApiResponse` 解析与一次性 401/403 会话失效回退。只有确认已登录后，才调用现有的 `loadRuntimeInfo()` 和其他管理数据加载函数。
- **Rationale**: 当前管理台没有 vue-router 或独立页面，条件渲染是最小、可验证的集成。复用既有深色/橙色 token，符合项目管理台的视觉与工程约束。
- **Alternatives considered**:
  - 新增路由框架和多页面登录页：拒绝，当前需求无需客户端路由，增加依赖和状态同步成本。
  - 继续在各处直接调用 `fetch`：拒绝，无法一致处理 CSRF、会话过期和统一响应信封。

## Decision 8: 认证错误保持既有 ApiResponse 信封

- **Decision**: 认证失败使用统一 `ApiResponse` JSON：未登录为 401、无效 CSRF/禁止为 403、达到失败限制为 429、管理员未配置或审计存储不可用为 503。未知账号与错误密码均返回同一 401 文案。安全过滤链的 entry point/denied handler 与 Controller 异常处理使用相同信封。
- **Rationale**: 现有 Vue 代码以 `code/message/data/timestamp` 解析响应；过滤器层的裸 HTML 或空响应会令客户端状态失稳并泄露框架默认行为。
- **Alternatives considered**:
  - 使用 Spring Security 默认 HTML 错误页：拒绝，破坏 REST 契约和 SPA 错误处理。
  - 为认证单独定义错误格式：拒绝，会形成两套前端解析规则。

## Sources

- [Spring Security: CSRF for servlet applications](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Security: authentication persistence and session management](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html)
