# Feature Specification: Web Service 认证机制（最小 Auth）

**Feature Branch**: `012-web-auth`

**Created**: 2026-07-22

**Status**: Draft

**Input**: User description: "OryxOS 缺用户登录机制。给管理台加最小 Basic Auth，feature flag 默认关，不破'假设内网'宪法前提。REST API 不做 Basic Auth，auth 只管用户访问（管理台）。账号存 SQLite 新表，密码 BCrypt 哈希（用 spring-security-crypto，不引 Spring Security 全套）。CLI 加 `oryxos user` 子命令管账号。"

## 定位与宪法关系

- 核心阶段 Web Service spec（`specs/009-web-service/spec.md`）边界明确："认证鉴权（假设内网，扩展阶段补）"。本 feature **提前实现扩展阶段能力**，但不破坏核心阶段行为。
- 手段：feature flag `oryxos.web.auth.enabled` 默认 `false`。默认关 = 现状不变 = 不违宪。
- 不引 Spring Security 全套（尊重"自实现核心、不锁框架"宪法精神）。只引 `spring-security-crypto` 单 jar 做密码哈希，非 filter chain、非 autoconfig、非 RBAC。
- 不加新 Maven 模块（尊重 9 模块宪法）。塞 `oryxos-storage` + `oryxos-web` + `oryxos-cli`。
- 凭证不落地：密码哈希存 DB，明文密码 MUST NOT 写入代码、配置、日志、提交历史（宪法原则 VI）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 管理员启用认证保护管理台 (Priority: P1)

运维不想让管理台裸奔内网。改配置开 auth，加 admin 账号，重启后访问管理台必须登录。REST API 不受影响（业务系统调 `/api/v1/**` 继续无认证）。

**Why this priority**: 这是本 feature 存在的唯一理由——管理台是给人看的、可直接操作的入口，裸奔风险最高。REST API 后续单独做 API Key。开 auth 后管理台从"任意能访问内网者可操作"变成"需账密才能操作"，是核心价值。

**Independent Test**: 配 `auth.enabled=true` 但无账号 → 启动报清晰错误；跑 `oryxos user add admin` → 输密码 → 表里有 BCrypt hash 非明文；启动 `oryxos serve` → 浏览器访问 `/admin/` 跳 `/admin/login` → 输对进 SPA，输错留登录页；curl `-u admin:pw` 访问 `/admin/` → 200。REST API `/api/v1/health` 不需认证、`/api/v1/profiles` 也不需（确认 REST 不受 auth 影响）。

**Acceptance Scenarios**:

1. **Given** `auth.enabled=false`（默认），**When** 访问 `/admin/`，**Then** 不弹登录、直接进（现状不变，回归零破坏）。
2. **Given** `auth.enabled=true` 且 `web_users` 无 enabled 账号，**When** 启动服务，**Then** 启动失败并报清晰错误，提示先跑 `oryxos user add`（不静默裸奔）。
3. **Given** `auth.enabled=true` 且有 enabled 账号 admin，**When** 浏览器访问 `/admin/`，**Then** 跳 `/admin/login`（登录页）。
4. **Given** 登录页，**When** 输对账密提交，**Then** `POST /api/v1/auth/login` 返 200 + 设 `oryxos_session` cookie，跳 `/admin/` 加载 SPA。
5. **Given** 登录页，**When** 输错账密，**Then** 返 401，留登录页显示"用户名或密码错误"。
6. **Given** 账号 `enabled=false`（被禁用），**When** 用该账密登录，**Then** 401（禁用账号登不进）。
7. **Given** `auth.enabled=true`，**When** 访问 `/api/v1/health`，**Then** 不需认证、正常返回（健康端点免认证）。
8. **Given** `auth.enabled=true`，**When** 访问 `/api/v1/profiles` 等 REST 端点，**Then** 不需认证、正常返回（REST API 不做认证，后续 API Key 单独 PR）。
9. **Given** `auth.enabled=true`，**When** curl `-u admin:pw` 访问 `/admin/`，**Then** 200（Basic Auth 路径并存，curl/自动化可用）。

---

### User Story 2 - 管理员通过 CLI 增删账号 (Priority: P1)

管理员通过命令行管理账号，不直接碰数据库，不重启服务即可让新账号生效（每请求查 DB）。

**Why this priority**: 没有账号管理就没法用 US1——启用 auth 必须先有账号。跟 US1 并列 P1，是 US1 的前置依赖。

**Independent Test**: `oryxos user add alice` → 交互输密码（不回显）→ `oryxos user list` 看到 alice（不显密码）→ 开 auth 后 Basic Auth 用 alice 登录成功 → `oryxos user delete alice` → 再登录 401。

**Acceptance Scenarios**:

1. **Given** 服务运行中或已初始化 DB，**When** `oryxos user add alice`，**Then** 提示输密码（不回显）→ 提示确认密码 → 成功提示 → `web_users` 表有 alice 行，`password_hash` 字段是 BCrypt hash（非明文、每次 salt 不同）。
2. **Given** 已有 alice，**When** `oryxos user add alice`，**Then** 报错"用户已存在"，退出码非 0，不覆盖原密码。
3. **Given** 有 alice 与 admin，**When** `oryxos user list`，**Then** 列出用户名 + enabled 状态 + 创建时间，**MUST NOT** 显示任何密码字段。
4. **Given** 有 alice，**When** `oryxos user passwd alice`，**Then** 提示新密码（不回显）+ 确认 → 更新 hash → 旧密码登不进、新密码能进。
5. **Given** 有 alice，**When** `oryxos user delete alice`，**Then** 表删行 → 开 auth 后 Basic Auth 用 alice 401。
6. **Given** 无 alice，**When** `oryxos user delete alice` 或 `oryxos user passwd alice`，**Then** 报错"用户不存在"，退出码非 0。
7. **Given** 有 alice，**When** `oryxos user disable alice`，**Then** `enabled` 置 false → 登录 401。
8. **Given** 有 alice 且已被禁用（`enabled=false`），**When** `oryxos user enable alice`，**Then** `enabled` 置 true → 登录 200（对称 disable，禁用后可恢复）。
9. **Given** 无账号时，**When** `oryxos user list`，**Then** 输出空列表提示，退出码 0（不是错误）。

---

### User Story 3 - 管理员用浏览器登录管理台 (Priority: P1)

管理员在浏览器打开管理台，看到同官网风格的登录页，输账密登录后进 SPA 管理台，可登出。curl/自动化仍可用 Basic Auth。

**Why this priority**: Basic Auth 原生弹窗体验差、无登出。登录页是"可对外展示"的管理台入口，跟 US1/US2 并列 P1。

**Independent Test**: 开 auth + 有 admin 账号 → 浏览器访问 `/admin/` → 跳 `/admin/login` → 输对 → 跳 `/admin/` SPA 加载 → 点登出 → 跳回登录页。curl `-u admin:pw` 访问 `/admin/` → 200（Basic 仍可用）。

**Acceptance Scenarios**:

1. **Given** 未登录，**When** 浏览器访问 `/admin/`，**Then** 跳 `/admin/login`（filter 302 或前端路由守卫）。
2. **Given** 登录页，**When** 输对账密提交，**Then** `POST /api/v1/auth/login` 返 200 + 设 `oryxos_session` cookie，前端跳 `/admin/`，SPA 加载。
3. **Given** 登录页，**When** 输错账密，**Then** 返 401，留登录页显示"用户名或密码错误"（不区分用户名是否存在，防枚举）。
4. **Given** 已登录（有有效 session cookie），**When** 访问 `/admin/`，**Then** filter 认 session 通过，直接加载 SPA。
5. **Given** 已登录，**When** 点登出，**Then** `POST /api/v1/auth/logout` 清 session 行 + 清 cookie，跳 `/admin/login`。
6. **Given** session 过期（`expires_at < now`），**When** 访问 `/admin/`，**Then** filter 认 session 失效，跳登录页。
7. **Given** 用 curl `-u admin:pw`，**When** 访问 `/admin/`，**Then** 200（Basic 路径并存）。
8. **Given** 已登录，**When** `GET /api/v1/auth/me`，**Then** 返当前用户名（验证 session 有效）。

---

### Edge Cases

- **密码不回显**：`user add` / `user passwd` 输密码时终端不显示明文（用 `Console.readPassword`）。
- **密码长度下限**：空密码或短于 8 字符 MUST 被拒绝（给清晰提示，拒绝建号）。
- **BCrypt salt**：相同密码每次 hash 不同（不同 salt），校验走 `BCrypt.checkpw` / `DelegatingPasswordEncoder.matches`。
- **用户名规范**：空用户名、含空格、超 64 字符 MUST 被拒绝。
- **确认密码不一致**：`user add` / `user passwd` 两次输入不一致 MUST 报错、不落库。
- **DB 未初始化跑 user 命令**：MUST 提示先 `oryxos init`，或命令自身触发建表，不崩。
- **公网部署**：Basic Auth / session cookie 须前置 HTTPS（非本 feature 范围，文档说明）。
- **健康端点免认证**：`/api/v1/health` 即使 `auth.enabled=true` 也 MUST 免认证（k8s/监控探活依赖）。
- **管理台静态资源**：`/admin/assets/**` 等 SPA 静态资源是否也拦？**决定**：整个 `/admin/**` 前缀都拦（含 assets），否则浏览器加载 SPA 壳时不弹窗、调 API 时才弹，体验割裂。浏览器首次访问 `/admin/` 弹一次后，后续同域资源自动带凭据。
- **并发改密码**：两个管理员同时改同一账号 → 最后写者胜，DB 事务保证一致性，无额外锁。
- **session 过期**：`web_sessions` 带 `expires_at`，filter 查到过期 session 当作无 session（浏览器跳 `/admin/login`，curl 401），不自动续期（核心阶段简化；滑动续期留扩展阶段）。过期行由 filter 查到时顺手删（惰性清），登出删当前行，无后台定时清理线程。
- **session 吊销**：登出 POST `/api/v1/auth/logout` 删当前 session 行；改密码/禁用账号后旧 session 仍有效到过期（核心阶段不级联清，留扩展阶段）。
- **并发登录**：同一账号多次登录建多个 session（不互踢），核心阶段不做单点登录约束。
- **CSRF**：登录用 POST + `SameSite=Strict` cookie 防 CSRF；核心阶段不引 CSRF token（SameSite 够）。
- **登录页放行**：`/admin/login` 路径 filter MUST 放行（未登录也要能访问登录页 + 其静态资源）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供 feature flag `oryxos.web.auth.enabled`，默认 `false`。`false` 时管理台与 REST API 全部行为与现状一致（回归零破坏）。
- **FR-002**: `auth.enabled=true` 时，系统 MUST 仅对 `/admin/**` 路径前缀启用认证（Basic Auth + session 两条路径，见 FR-005）；`/api/v1/**` REST 端点 MUST 维持无认证（现状不变，仅 `/api/v1/auth/*` 子树是认证专用端点）。Basic Auth realm MUST 取配置值 `oryxos.web.auth.realm`（默认 "OryxOS"，curl 401 时 `WWW-Authenticate` 用）。
- **FR-003**: 系统 MUST 用 `web_users` 表存账号，字段含 `username`（唯一）、`password_hash`（BCrypt）、`enabled`、时间戳。明文密码 MUST NEVER 写入数据库、配置、日志或提交历史（宪法原则 VI）。
- **FR-004**: 系统 MUST 提供 CLI 命令组 `oryxos user`，含子命令 `add`、`list`、`delete`、`passwd`、`disable`、`enable`，走 Picocli，与现有 CLI 命令同风格。密码输入 MUST 不回显。
- **FR-005**: 认证校验流程 MUST 支持两条路径，任一通过即放行：(a) Basic Auth——取 `Authorization: Basic` 头 → Base64 解码拆 user/pass → 按 username 查 `web_users` → 用 `DelegatingPasswordEncoder.matches` 比对密码 → 账号 `enabled` 必须 true；(b) Session——取 `oryxos_session` cookie → 按 session id 查 `web_sessions` → session 未过期（`expires_at >= now`）即放行（**不**顺带查 `web_users` 的 enabled——Clarifications Q1 决定：改密/禁用后旧 session 仍有效到过期，不级联查 user 表）。两者都无/都失败：浏览器（`Accept` 头含 `text/html`）→ 302 跳 `/admin/login`；curl/自动化 → 401 + `WWW-Authenticate: Basic realm="<配置值>"`。
- **FR-006**: `auth.enabled=true` 且 `web_users` 无任何 enabled 账号时，服务启动 MUST 报清晰错误并阻断启动（不静默裸奔），提示运行 `oryxos user add`。
- **FR-007**: 增 / 删 / 改账号 MUST NOT 重启服务即生效——每次认证请求 MUST 实时查 DB（不进程内缓存账号表）。
- **FR-008**: `/api/v1/health` 端点 MUST 免认证（即使 `auth.enabled=true`），保障 k8s/监控探活。
- **FR-009**: 密码哈希 MUST 用 `spring-security-crypto` 的 `DelegatingPasswordEncoder`（hash 带 `{bcrypt}` 前缀），为将来升级 Argon2 等算法预留无迁移切换路径。MUST NOT 引入 Spring Security 全套（无 filter chain、无 autoconfig、无 RBAC）。
- **FR-010**: 建表 MUST 走手工脚本（`schema.sql` 或等价），MUST NOT 依赖 Hibernate `ddl-auto=update` 自动迁移（宪法原则 VIII，SQLite `ALTER TABLE` 支持弱）。
- **FR-011**: `oryxos user` 命令 MUST 做输入校验：空用户名、含空格、超 64 字符拒绝；空密码或短于 8 字符拒绝；两次密码输入不一致拒绝。
- **FR-012**: `oryxos user list` 输出 MUST NEVER 包含密码或哈希字段，仅用户名 / enabled / 创建时间。
- **FR-013**: 系统 MUST 暴露 `POST /api/v1/auth/login`（接 JSON `{username, password}`，验账密 → 建 `web_sessions` 行 → 设 `oryxos_session` cookie `HttpOnly`+`SameSite=Strict`+`Secure`(HTTPS 时)），失败返 401 统一信封；`POST /api/v1/auth/logout`（清当前 session 行 + 清 cookie）；`GET /api/v1/auth/me`（返当前登录用户名，未登录 401）。
- **FR-014**: 系统 MUST 用 `web_sessions` 表存 session，字段含 `session_id`（UUID，唯一）、`username`、`expires_at`、`created_at`。session id 由安全随机生成（`UUID.randomUUID` 或等价）。
- **FR-015**: session 过期时间 `oryxos.web.auth.session-ttl`（默认 12 小时，可配）；filter 查到 `expires_at < now` 的 session 当作无 session（不自动续期）。
- **FR-016**: 管理台前端 MUST 有 `LoginView.vue`（`/admin/login` 路由，同官网首页设计风格：深色 + 橙、同字体），未登录访问 `/admin/**`（除 `/admin/login` 与静态资源）→ 前端路由跳 `/admin/login`。登录成功跳 `/admin/`。登录失败显示"用户名或密码错误"（不区分用户名存在与否，防枚举），密码框清空、用户名框保留。
- **FR-017**: `/admin/login` 路径（含其静态资源）filter MUST 放行，让未登录用户能加载登录页。
- **FR-018**: 登录页 SPA MUST 无独立后端模板，纯 Vue 组件调 `/api/v1/auth/login`，复用 `web_users` 账号体系。

### Key Entities *(include if feature involves data)*

- **WebUser**：管理员账号实体。关键属性：用户名（唯一标识）、密码哈希（BCrypt，非明文）、启用状态、创建时间、更新时间。一个 WebUser 对应一个可登录管理台的管理员。
- **BasicAuth 凭据**：HTTP `Authorization: Basic <Base64(user:pass)>` 头，由浏览器在弹窗后或 curl 自动携带，服务端拆解校验（curl/自动化路径）。
- **Session**：`web_sessions` 表一行，含 session id（UUID cookie 值）、username、`expires_at`、`created_at`。浏览器走此路径（登录页建 session → cookie 后续携带）。
- **`oryxos_session` cookie**：HttpOnly + SameSite=Strict + Secure(HTTPS)，值 = session id（UUID）。
- **Auth 配置开关**：`oryxos.web.auth.enabled` + `oryxos.web.auth.realm` + `oryxos.web.auth.session-ttl`，控制认证是否启用、realm 文案、session 过期。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: `auth.enabled=false`（默认）时，管理台与 REST API 100% 行为与未加 auth 前一致（前序测试全绿、回归零破坏）。
- **SC-002**: `auth.enabled=true` 且存在 enabled 账号时，无凭据 curl 访问 `/admin/` 100% 返 401 + `WWW-Authenticate`；正确 Basic 凭据 100% 返 200；浏览器无 session 访问 `/admin/` 跳 `/admin/login`。
- **SC-003**: `auth.enabled=true` 时，`/api/v1/**` REST 端点 100% 不受 `/admin/**` filter 认证影响（无凭据也正常返回，含 `/api/v1/health`）；`/api/v1/auth/*` 子树是认证专用端点（login/logout/me），靠 controller 自身校验，非 `/admin/**` filter 拦。
- **SC-004**: 数据库中密码字段 0% 明文、100% BCrypt hash；`oryxos user list` 输出 0% 含密码或哈希字段。
- **SC-005**: `oryxos user add/list/delete/passwd/disable/enable` 全部子命令可正常运行；加账号后无需重启服务，下一次认证请求即可用新账号登录。
- **SC-006**: `auth.enabled=true` 且无 enabled 账号时，服务启动 100% 阻断并报清晰错误（不静默放行）。
- **SC-007**: 管理员完成"开 auth + 建首账号 + 登录"全流程在 3 分钟内（配置改一行 + 一条 CLI 命令 + 浏览器登录页登录）。
- **SC-008**: 前序全部 spec 测试保持全绿（本 feature 默认关，不改前序契约）。
- **SC-009**: 浏览器访问 `/admin/`（未登录）→ 跳 `/admin/login` → 输对账密 → 跳 `/admin/` 加载 SPA；输错 → 留登录页显示错误；登出 → 跳回登录页 + 清 cookie。
- **SC-010**: session 过期后访问 `/admin/` → 跳登录页（filter 认过期 session 失效）。
- **SC-011**: curl 走 Basic Auth（`-u user:pass`）仍可访问 `/admin/`（两种认证路径并存，curl 200）。

## Assumptions

- `web_users` 表走现有 SQLite + Spring Data JPA，与 sessions / 审计表同数据源（`oryxos.db`，工作目录根，以 `application.yml` 实际配置为准）。
- 密码哈希用 `org.springframework.security:spring-security-crypto` 单 jar（不引 `spring-boot-starter-security` 全套），其 `DelegatingPasswordEncoder` 提供 `{bcrypt}` 前缀与将来 Argon2 升级路径。
- Basic Auth 适合内网 + HTTPS 场景；公网部署需前置 HTTPS（非本 feature 范围，文档说明）。
- `oryxos user` 命令走 Picocli，与现有 12 命令同风格；命令访问 DB 需 Spring 上下文（沿用现有需 Spring 的命令的启动模式）。
- 管理台前端为 Vue SPA，托管在 `/admin/**`（由 `oryxos-web` 的 `WebConfig` 静态资源映射）。
- **登录页**：浏览器走 session/cookie（SPA 登录页 `/admin/login`），curl/自动化走 Basic Auth（两者并存，filter 都认）。session 存 SQLite 新表 `web_sessions`（重启不丢、单机够用；多实例共享留扩展阶段 Redis）。cookie `HttpOnly`+`Secure`(HTTPS)+`SameSite=Strict`。
- REST API 的机器调用认证（API Key）为后续独立 PR，本 feature 不涉及。
- Session/Cookie 登录、登出、超时在本 feature 做（登录页配套）；JWT、OAuth2/SSO、RBAC、多租户均为扩展阶段，本 feature 不做；`web_users` 表不预留 `roles` 字段（YAGNI，到扩展阶段再加）。
- 本 feature 提前实现扩展阶段能力（虽默认关），需在上游 PR 描述中说明并征得 maintainer 同意。

## Clarifications

### Session 2026-07-22

- Q: 禁用账号或改密码后，已发放的 session 是否立即失效？ → A: B 不失效，旧 session 有效到过期（12h）。核心阶段不级联清，filter 查 session 不顺带查 user 表（简单，有 12h 安全窗口，内网可控）。改密码/禁用后旧 session 仍到过期，扩展阶段再加级联失效。
- Q: 浏览器访问 `/admin/`（未登录）时，filter 如何让其跳登录页？ → A: A 分流——filter 检测 Accept 头含 `text/html`（浏览器）→ 302 跳 `/admin/login`；否则（curl/自动化）→ 401 + `WWW-Authenticate: Basic realm="..."`。后端单一真相按客户端分流，前端 SPA 路由守卫兜底（直接访问 `/admin/<sub>` 子路由也守卫跳登录）。
- Q: 登录失败时，前端 LoginView 错误文案 + 是否清空密码框？ → A: A 文案"用户名或密码错误"（不区分用户名存在与否，防枚举），密码框清空、用户名框保留。标准登录页体验。
- Q: session 存 SQLite，过期 session 行怎么清？ → A: B 惰性清——登出删当前行 + filter 查到过期行顺手删当前行，无后台定时线程。简单，过期行可能累积到被访问才清（扩展阶段可加定时清）。
- Q: 登录页视觉风格"同官网首页"，具体复用哪些？ → A: A 复用 `oryxos-web` frontend 现有 design tokens（`src/styles/tokens.css`，深色 + 橙品牌色 + Space Grotesk 字体），LoginView 居中卡片（logo + 用户名/密码输入 + 登录按钮），全用 token 变量。与官网首页/管理台同视觉。
