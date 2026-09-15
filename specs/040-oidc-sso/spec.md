# Feature Specification: OIDC/SSO 登录与企业身份映射

**Feature Branch**: `040-oidc-sso`

**Created**: 2026-09-15

**Status**: Draft（3 项待澄清，等维护者拍板）

**Input**: Issue #461（epic #454「企业控制面」子项）：支持标准 OIDC 登录，建立外部 subject 到 OryxOS 用户、团队的映射。验收：授权码 + PKCE；未授权身份不能访问受保护 API/管理台；登录、登出、身份映射留审计记录。

> 前置：#462 第一刀已合（#484/#485/#488）——统一主体 `Principal{kind,id,displayName,roles}`、`AuthorizationService` 唯一决策点、VIEWER⊆EDITOR⊆ADMIN 角色矩阵、两扇自研 Filter（BasicAuthFilter `/admin/**`、ApiKeyAuthFilter `/api/**`）。本刀让企业用户以既有 IdP 身份登录管理台，外部身份落进这套主体模型。宪法边界不变：**不引入 Spring Security 全套**，OIDC 流程在既有自研 Filter 体系内实现。
>
> **探索校准（2026-09-15 代码走查）**：RBAC 授权目前只接线在 ApiKeyAuthFilter（`/api/**`，含管理台 session 互认路径）；BasicAuthFilter（`/admin/**` 静态面）尚未置入 Principal（039 遗留缺口）——本刀映射出的角色经 `/api` 面即时生效，`/admin` 静态资源面仅认证不授权，是否随本刀补主体置入留 plan 裁量。管理台会话为自研落库形态（`web_sessions` 表 + `oryxos_session` HttpOnly cookie），非 servlet HttpSession——OIDC 登录复用该会话形态即天然多副本可用。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 企业用户经 IdP 单点登录管理台（Priority: P1）

企业已有标准 OIDC IdP（Keycloak / Azure AD / Authing / 飞书开放平台等，凡支持 OIDC Discovery 的均可）。管理员在 OryxOS 配置一组 IdP 参数（issuer、client_id 等）后：用户访问管理台未登录时可选择「企业账号登录」→ 跳转 IdP 认证 →（授权码 + PKCE）回调 OryxOS → 建立管理台会话，进入与本地账号登录完全一致的使用体验。登出时结束 OryxOS 会话（并按配置可选联动 IdP 端登出）。IdP 侧禁用/删除的账号，其已有会话到期后无法再登录。

**Why this priority**: 「未授权身份不能访问」的企业级实现前提就是接入企业统一身份——这是 #454 控制面的第一扇门，也是本刀存在理由。

**Independent Test**: 本地起标准 OIDC 测试 IdP（如 Keycloak 容器或内嵌 mock IdP），配置后完成登录→访问受保护页→登出全流程；错误路径（state 不符、code 换 token 失败、nonce 不符、IdP 拒绝）各自给出可读失败且不建会话。

**Acceptance Scenarios**:

1. **Given** 已配置 IdP 且用户在 IdP 有有效账号，**When** 用户点击企业登录并在 IdP 完成认证，**Then** 回调后建立 OryxOS 会话、进入管理台，身份显示为映射后的 OryxOS 用户
2. **Given** 授权码流程被篡改（state/nonce 不匹配、回调码伪造），**When** 回调到达，**Then** 拒绝建会话并给出不泄露细节的可读错误，同时留审计
3. **Given** 未登录用户，**When** 直接访问受保护管理台路径或调用受保护 API，**Then** 一律拒绝（跳登录/401），行为与既有 Basic Auth 档一致
4. **Given** 已登录用户点击登出，**When** 登出完成，**Then** OryxOS 会话即刻失效，再访问受保护面需重新登录
5. **Given** 未配置 OIDC（默认），**When** 正常使用，**Then** 行为与本特性交付前完全一致（Basic Auth/API Key 现状零变化）

---

### User Story 2 - 外部身份到 OryxOS 用户与角色的映射（Priority: P1）

IdP 认证通过后，OryxOS 依据 ID Token 的 subject（及可配置的 claims）确定「这是哪个 OryxOS 用户、拥有什么角色」：映射结果落进 #462 的 `Principal{kind,id,roles}` 统一主体——后续每个请求的授权判定与本地账号无差别。管理员可配置「IdP claim 值 → OryxOS 角色」的映射规则（如 IdP group `oryxos-admins` → ADMIN）。同一外部身份重复登录稳定映射到同一 OryxOS 用户。

**Why this priority**: 没有映射，登录只是「进了门却没有身份」；这是与 #462 骨架的衔接点，与 US1 同为 P1。

**Independent Test**: 配置 claim→role 映射后，不同 IdP 用户登录分别获得对应角色；越权访问被 RBAC 拒绝并落 authz 审计；映射规则改动后按 [C3 待澄清的角色权威口径] 生效。

**Acceptance Scenarios**:

1. **Given** IdP 用户携带映射规则命中的 claim，**When** 登录成功，**Then** 会话主体的角色为映射结果，受保护操作按 #462 矩阵放行/拒绝
2. **Given** 同一 IdP subject 第 N 次登录，**When** 映射执行，**Then** 恒定对应同一 OryxOS 用户标识（审计可跨登录关联）
3. **Given** [C1 待澄清] IdP 认证通过但 OryxOS 侧无对应用户，**When** 回调处理，**Then** 按拍板结果执行（自动供给 / 拒绝并指引）
4. **Given** claim 不命中任何映射规则，**When** 登录，**Then** 按最小权限兜底（获得配置的默认角色或被拒，随 C1/C3 拍板联动）

---

### User Story 3 - 认证事件全程留审计（Priority: P2）

登录成功、登录失败（含各类协议错误）、登出、身份映射结果（首次映射/角色变化）均落审计事件——运维可回答「谁、什么时候、以什么身份、从哪个 IdP 进来的、拿到了什么角色」。审计写入失败不阻断登录主链路（fail-open + 独立告警，与既有审计纪律一致）。

**Why this priority**: issue 验收第三条；也是 #462 第一刀「认证事件审计留给 #461」的移交项兑现。

**Independent Test**: 全流程走查后按事件类型查询审计，各场景恰好对应记录、字段齐全（时间/subject/映射用户/角色/来源 IP/结果/失败原因分类）；放行类高频事件不产生噪音式落库。

**Acceptance Scenarios**:

1. **Given** 一次成功登录，**When** 查询审计，**Then** 有 login_success 事件含外部 subject、映射用户、获得角色
2. **Given** 一次失败登录（IdP 拒绝/协议校验失败），**When** 查询审计，**Then** 有 login_failure 事件含失败原因分类（不含敏感令牌内容）
3. **Given** 登出与首次身份映射，**When** 查询审计，**Then** 各有对应事件；重复登录的稳定映射不重复记 mapping 事件（仅角色变化时记）

---

### Edge Cases

- **IdP 不可达/宕机**：登录入口给出可读错误；已建会话不受影响；[C2 拍板的兜底通道] 保证管理员不被锁死
- **回调重放**：授权码一次性 + state 单次消费，重放拒绝并审计
- **时钟偏移**：ID Token 时间类校验允许小偏移窗口（配置化，默认 60s）
- **会话时长**：OryxOS 会话时长独立配置（默认沿既有会话口径），不追随 IdP token 过期时间逐秒同步；IdP 侧撤权在会话到期后生效（本刀不做 back-channel logout / 会话即时吊销，见「不做」）
- **多副本**：登录回调可落任一副本——state/nonce 与会话的存储必须多副本可用（026/027 共享事实源纪律）；单机档零配置不变
- **HTTPS**：回调地址生产环境要求 https（反向代理终止 TLS 的 forward-headers 既有机制适用）；本地开发允许 http 回环
- **邮箱/subject 变更**：映射锚点用 IdP `sub`（稳定标识），不用 email（可变）；email 仅作展示与辅助匹配 [与 C1 联动]
- **开启 OIDC 但配置残缺**：启动即拒并点名缺失项（沿 ProviderStartupCheck/RbacStartupCheck 惯例）

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 支持标准 OIDC 授权码 + PKCE 流程（S256）：经 Discovery（`/.well-known/openid-configuration`）获取端点，state + nonce 双校验，ID Token 签名经 IdP JWKS 验证；不实现隐式流/密码流
- **FR-002**: OIDC MUST 为可选配置（默认关=现状零变化）；开启时配置残缺启动即拒并指明缺失项
- **FR-003**: 登录成功 MUST 产出与本地登录同构的管理台会话（复用既有落库会话形态）；会话主体为 #462 的 `Principal`，且系统 MUST 能区分该身份来自 OIDC（区分形态——扩展 kind 或来源标记——留 plan）；后续授权判定与本地账号无差别路径
- **FR-004**: 身份映射 MUST 以 IdP `sub` 为稳定锚点（同 subject 恒映射同一 OryxOS 用户）；MUST 支持「claim 值 → OryxOS 角色」映射规则配置；无命中时按最小权限兜底 [细则随 C1/C3 拍板]
- **FR-005**: 未认证或映射失败的身份 MUST NOT 获得任何受保护面访问；错误提示 MUST NOT 泄露 IdP 交互细节与令牌内容
- **FR-006**: 登出 MUST 即刻失效 OryxOS 会话；IdP 端 RP-Initiated Logout 为可选配置
- **FR-007**: 登录成功/失败/登出/映射变化 MUST 落审计事件（含外部 subject、映射用户、角色、来源、失败分类），写入失败不阻断主链路
- **FR-008**: state/nonce 及回调所需临时状态 MUST 多副本可用（任一副本可处理回调）；单机档零新增运维件
- **FR-009**: ID Token / access token MUST NOT 落日志与审计明文；JWKS 缓存 MUST 有刷新与 kid 轮换容错
- **FR-010**: 与既有认证的共存关系按 [C2 拍板] 执行；API Key 面（REST 机器调用）不受本刀影响
- **FR-011**: 管理台前端 MUST 提供企业登录入口（IdP 配置存在时显示），登录/登出交互与既有页面体系一致

### Key Entities *(include if feature involves data)*

- **IdP 配置**: issuer、client_id、client_secret（022 加密面）、scopes、claim 映射规则、可选登出端点——配置形态待 plan（yaml vs 落库管理台可编辑）
- **身份映射记录**: 外部 `iss+sub` ↔ OryxOS 用户的持久关联（含首次/最近登录时间）——稳定映射与审计关联的载体
- **认证事件**: login_success / login_failure / logout / mapping_changed，与 #462 authz_events 同族形态
- **临时授权状态**: state/nonce/PKCE verifier 的短时存储（TTL 分钟级），多副本可见

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 对照标准 OIDC 测试 IdP，登录→受保护访问→登出全流程走通；五类协议错误路径（state/nonce/签名/过期/IdP 拒绝）全部拒绝且各留一条分类审计
- **SC-002**: 未配置档全量既有测试零改动全绿（Basic Auth/API Key/RBAC 现状零变化）
- **SC-003**: claim→role 映射三档（ADMIN/EDITOR/VIEWER）各自登录后，越权操作被 #462 矩阵拒绝且落 authz_events——两刀衔接闭环
- **SC-004**: 同一 subject 跨 100 次登录映射稳定同一用户；审计可按外部 subject 串联全部登录史
- **SC-005**: 双副本档：登录发起与回调落不同副本时流程照常成功（临时状态共享验证）
- **SC-006**: 令牌类内容在日志/审计/错误响应中零出现（走查 + 审计字段审查）

## Assumptions

- **通用 OIDC 而非特定 IdP 适配**：只依赖标准 Discovery/授权码/PKCE/JWKS，任何合规 IdP 即插即用；特定 IdP 的私有扩展（飞书租户键等）不做
- **复用既有会话与用户模型**：OIDC 登录只是既有落库会话（`web_sessions`）的另一种建立方式；映射目标沿用既有 `web_users` 用户模型（角色列已就位），不另立平行用户体系——FR-008 的多副本临时状态仅剩 state/nonce/PKCE verifier
- **认证事件为新增审计族**：现有 `authz_events` 只记授权拒绝且 schema 不适配认证语义（无事件类型/结果/来源 IP/IdP 字段），认证事件另立同族新表（探索确认 039 移交项属实）
- **「团队」映射降格**：issue 范围提到映射到「团队」——团队/租户实体不存在（#462 spec 明确三级租户为后续刀），本刀映射到 **用户 + 角色**；团队维度留待租户模型立项时扩展映射规则
- **API 面不变**：OIDC 只服务管理台人类登录；REST 机器调用继续 API Key（018），不做 OIDC access token 打 API
- **不引入 Spring Security / spring-boot-starter-oauth2-client**：沿两扇自研 Filter 体系与宪法边界；OIDC 协议实现按 plan 阶段选型（标准 JOSE 库做签名验证是允许的管道复用）
- **不做**：SAML、SCIM 用户同步、back-channel logout/会话即时吊销、多 IdP 并存（首刀单 IdP）、IdP 发起的登录（IdP-initiated）、记住我/长会话

## 待澄清（3 项，等维护者拍板后进 plan）

> 集中列在此处；每项的候选与影响见交付说明。拍板后本节内容并入 FR/Assumptions 并删除本节。

- **C1 未映射用户的处置**（关联 US2 场景 3/4、FR-004）
- **C2 与 Basic Auth 的共存关系**（关联 FR-010、Edge「IdP 宕机锁死」）
- **C3 角色的权威来源**（关联 US2、FR-004、#462 衔接语义）
