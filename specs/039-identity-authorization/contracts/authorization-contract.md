# Contract: 授权决策与主体语义

**Feature**: 039-identity-authorization | **Date**: 2026-09-14 | 原则 VI：安全是地基（最小权限 / 默认拒绝 / 不给凭证可乘之机）

## 1. 主体与裁决语义承诺

1. **唯一主体**：一次请求的「谁」只有一个表达——`io.oryxos.core.auth.Principal{kind, id, displayName, roles}`；`kind ∈ {USER, API_KEY, ANONYMOUS}`，**主体永远不是 `null`**（未认证归一为 `Anonymous(anonymous)`）。任何从 `sessions.user_id` 推导主体的实现都是错的——该列被控制台写成常量 `default`。
2. **唯一决策点**：`AuthorizationService` 只有一个实现（`RoleBasedAuthorizationServiceImpl`），API / 管理台 / 运行时三条路径都调它；Controller 与 Filter **不得**直接比对角色做判定。
3. **只做减法**：裁决结果永远 ⊆ 主体角色所允许的动作集合；没有任何「临时提权」通道——同请求里带 session 与 Key 时**角色不取并集**。
4. **确定收敛**：同输入同结果；矩阵是代码常量（`EnumSet` 逐级叠加），不是运行时可改数据；扩档先改 spec。
5. **拒绝可解释**：`Decision.reason()` 是能被人读懂的一句话，直接进审计与拒绝响应；错误码不充当理由。
6. **默认关零破坏**：`oryxos.web.rbac.enabled` 缺省 `false`，此时注入 `AuthorizationService.ALLOW_ALL`，行为与引入授权层之前逐字节一致。
7. **术语消歧**：本契约里的「**边界 / boundary**」指**本部署的授权范围整体**（对应 `Action.MANAGE_WORKSPACE`、`ResourceRef.TYPE_WORKSPACE`），与 `.oryxos/`（Agent 工作区根目录，`oryxos.root`）**无关**；租户/组织/项目的正式命名待 #462 的租户模型裁决（见 spec.md §Clarifications）。

## 2. 最小角色矩阵（本契约的正典）

| Action | VIEWER | EDITOR | ADMIN | API_KEY 上限 |
|--------|:------:|:------:|:-----:|:------------:|
| `READ_WORKSPACE` | ✅ | ✅ | ✅ | ✅ |
| `READ_AUDIT` | ✅ | ✅ | ✅ | ✅ |
| `RUN_AGENT` | ✗ | ✅ | ✅ | ✅ |
| `MANAGE_AGENTS` | ✗ | ✅ | ✅ | ✅ |
| `MANAGE_KNOWLEDGE` | ✗ | ✅ | ✅ | ✅ |
| `MANAGE_SKILLS` | ✗ | ✅ | ✅ | ✅ |
| `MANAGE_SESSIONS` | ✗ | ✅ | ✅ | ✅ |
| `MANAGE_WORKSPACE` | ✗ | ✗ | ✅ | ✅ |
| `MANAGE_CHANNELS` | ✗ | ✗ | ✅ | ✅ |
| `MANAGE_POLICIES` | ✗ | ✗ | ✅ | **✗** |
| `MANAGE_MEMBERS` | ✗ | ✗ | ✅ | **✗** |

- `VIEWER ⊆ EDITOR ⊆ ADMIN` 逐级包含；实现用 `EnumSet` 叠加，避免「给 EDITOR 加了能力却忘给 ADMIN」的矩阵漂移。
- **API Key 主体能力上限**：即使被授予 `ADMIN`，`MANAGE_MEMBERS` 与 `MANAGE_POLICIES` 也不放行——不让一把可复制的长期机器凭证去改治理规则本身（含「给攻击者自己发权限」这条路径）。
- 主体未携带角色时按配置默认档兜底：`oryxos.web.rbac.roles.default-user-roles` 默认 `[]`（**phase3 tighten**：角色已落库后空默认档，无角色即拒绝；靠 `RbacStartupCheck` 防无 ADMIN 锁死），`oryxos.web.rbac.roles.default-api-key-roles` 默认**空**（机器凭证不默认授权）。

## 3. 配置契约

```yaml
oryxos:
  web:
    rbac:
      enabled: false                   # 默认关：零行为变化（FR-005，宪法级约束）
      deny-anonymous: true             # 授权启用时匿名主体默认拒绝
      roles:                           # 独立前缀：与 rbac.* 同前缀会在启动时绑定冲突
        default-user-roles: []         # phase3：空默认档（无角色即拒绝）；防锁死靠 RbacStartupCheck
        default-api-key-roles: []      # 默认空 = 机器凭证不默认授权（放行需显式授予，如 [EDITOR]）
```

启动校验（FR-012，均不静默）：

| 条件 | 行为 |
|------|------|
| `rbac.enabled=true` 且 `web.apikey.enabled=false` | **拒绝启动**：没有门会产出主体，授权会静默失效；报错指路「先开 `oryxos.web.apikey.enabled`」 |
| `rbac.enabled=true` 且无任何 ADMIN 账号 | **拒绝启动**：治理面锁死；报错指路 `oryxos user role <name> ADMIN` |
| `rbac.enabled=true` 且 `web.auth.enabled=false` | WARN：管理台数据面不可用（沿用 018 同组合的告警口径，不重复 fail） |
| `web_users.roles` 出现未知 token | WARN + 忽略该 token（降权）；不得向上兜底 |
| `/api/v1|v2/**` 存在未映射且未登记豁免的端点 | **拒绝启动**，打印路径清单（把手维护遗漏变成部署期显式失败） |

## 4. 请求裁决契约（flag 开启时）

> ### ⚠️ 实现阶段状态（读本节前必看）
>
> **本节 §4.1 / §4.2 描述的是「阶段二目标态」，不是当前已实现的行为。**
>
> | 能力 | 当前状态 |
> |------|----------|
> | 阶段一（**已实现**） | 所有受保护路径一律只要求基线动作 `READ_WORKSPACE`（即 VIEWER 及以上放行、匿名与零角色拒绝）。**没有**路径→动作的差异裁决，`RequestActionResolver` 与「未登记路径 fail-closed」**都不存在**。 |
> | 阶段二（**本刀目标**，见 `tasks.md` T019~T023） | §4.1 流程图与 §4.2 全表生效：按资源族 × 方法区分动作，未登记路径拒绝。补登记：`/api/v2/schedules/**` 与 v1 调度同档；`POST /api/v1`（Alipay 截断网关）与 `channels/inbound` 同口径只认证不裁决。 |
>
> **由此产生的一个真实误判风险**：只读本契约、不读 `tasks.md` 的读者会以为差异裁决已生效——例如以为 `EDITOR` 访问 `/api/v1/tool-policy/**` 会 403，而**当前实际能通过**（因为基线只要求 VIEWER）。评审与验收请以 `acceptance-report.md` 的 SC 对照表为准。

### 4.1 裁决流程

```
请求 → 既有 Filter 认证（012 / 018 语义不变）
     → 成功分支：PrincipalHolder.set(request, principal) → RbacEnforcer.authorize(...)
         ├─ rbac.enabled=false → 放行（零差异）
         ├─ 登记豁免路径 → 不裁决（认证面/存活面）
         ├─ 匿名主体且 deny-anonymous=true → 403 + 审计
         ├─ RequestActionResolver 命中 → AuthorizationService.decide(...) → 允许放行 / 拒绝 403 + 审计
         └─ 未登记路径 → 403 + 审计（fail-closed）
     → 认证失败：原样返回既有 401（挑战头、响应体、浏览器 302 全不变）
```

**零 URL pattern 变更**：`AuthFilterConfig` 的 `/admin/*` 与 `ApiKeyFilterConfig.PROTECTED_URL_PATTERNS`（`/api/v1/*`、`/api/v2/*`、`/actuator/*`）一字不改；`/admin/**` 继续**只认证不裁决**（控制台数据面走 `/api/v1/**`，天然落在同一决策点上）。

### 4.2 路径 → 动作映射表（唯一的登记处）

| 路径 / 条件 | 方法 | 裁决 |
|-------------|------|------|
| `OPTIONS` 任意路径 | OPTIONS | 不裁决（CORS 预检，与 018 一致） |
| `/api/v1/health` | 任意 | 不裁决（存活面；豁免清单与 018 逐条一致） |
| `/api/v1/auth/**` | 任意 | 不裁决（登录/登出/me 必须可用，否则无人能取得主体；同 018 豁免定位） |
| `/actuator/health`、`/actuator/health/*` | 任意 | 不裁决（同 018） |
| `/actuator/**`（其余：`prometheus`、`metrics`、`info`） | 任意 | `READ_WORKSPACE`（观测面，任何已认证档可读） |
| `/api/v1/channels/inbound/**` | 任意 | **不裁决（只认证）**——调用方是 IM 平台而非人，主体是部署级 Key；平台侧真实性由适配器自身签名校验负责（017 契约）。纳入 `MANAGE_CHANNELS` 会让 RBAC 一开就打挂线上渠道 |
| `POST /api/v1/agents/{name}/invoke` | POST | `RUN_AGENT` + `ResourceRef.agent(name)` |
| `/api/v1/sessions/**`、`/api/v1/runs/**` | 任意 | `MANAGE_SESSIONS` |
| `/api/v1/agents/**`（除 invoke） | GET / HEAD | `READ_WORKSPACE` |
| `/api/v1/agents/**`（除 invoke） | 其它 | `MANAGE_AGENTS` + `ResourceRef.agent(name)` |
| `/api/v1/knowledge/**` | GET / HEAD | `READ_WORKSPACE` |
| `/api/v1/knowledge/**` | 其它 | `MANAGE_KNOWLEDGE` + `ResourceRef.knowledge(name)` |
| `/api/v1/skills/**` | GET / HEAD | `READ_WORKSPACE` |
| `/api/v1/skills/**` | 其它 | `MANAGE_SKILLS` + `ResourceRef.skill(name)` |
| `/api/v1/personas/**` | GET / HEAD | `READ_WORKSPACE` |
| `/api/v1/personas/**` | 其它 | `MANAGE_AGENTS`（人格是 Agent 定义的一部分） |
| `/api/v1/schedules/**` | GET / HEAD | `READ_WORKSPACE` |
| `/api/v1/schedules/**` | 其它 | `MANAGE_AGENTS`（调度属于 Agent 定义） |
| `/api/v2/schedules/**`、`POST /api/v2/agents/{profile}/schedules/{key}/run` | GET / HEAD | `READ_WORKSPACE`（与 v1 调度同档；盘点补登记） |
| `/api/v2/schedules/**`、`POST /api/v2/agents/{profile}/schedules/{key}/run` | 其它 | `MANAGE_AGENTS` |
| `POST /api/v1`、`POST /api/v1/` | POST | **不裁决（只认证）**——支付宝截断网关兼容口，与 `channels/inbound` 同属外部平台回调 |
| `/api/v1/channels/**`（除 inbound）、`/api/v1/notify-channels/**`、`/api/v1/mcp-servers/**` | 任意 | `MANAGE_CHANNELS` + `ResourceRef.channel(name)` |
| `/api/v1/tool-policy/**`、`/api/v1/sandbox/**` | 任意 | `MANAGE_POLICIES` + `ResourceRef.policy()` |
| `/api/v1/audit/**` | 任意 | `READ_AUDIT` + `ResourceRef.audit()` |
| `/api/v1/workspace/**` | GET / HEAD | `READ_WORKSPACE` |
| `/api/v1/workspace/**` | 其它 | `MANAGE_WORKSPACE` + `ResourceRef.workspace()` |
| `/api/v1/providers/**`、`/api/v1/pricing/**` | GET / HEAD | `READ_WORKSPACE`（凭证以掩码返回） |
| `/api/v1/providers/**`、`/api/v1/pricing/**` | 其它 | `MANAGE_WORKSPACE`（部署级设置与凭证面，归 ADMIN） |
| `/api/v1/info`、`/api/v1/profiles`、`/api/v1/tools`、`/api/v1/instances` | GET / HEAD | `READ_WORKSPACE`（观测/清单面，任何已认证档可读） |
| 其余 `/api/v1|v2/**`（未登记） | 任意 | **拒绝 + 审计**（fail-closed）；启动期全覆盖校验应已把这类路径变成启动失败 |

### 4.3 拒绝响应（403）

```
HTTP/1.1 403 Forbidden
Content-Type: application/json

{"code": 403, "message": "角色不足以执行该动作：MANAGE_CHANNELS on channel:feishu", "data": null, "timestamp": ...}
```

- 所有拒绝原因共用同一形态；`message` 为人话理由（`Decision.reason`），可含动作与资源类型，**不含**任何凭证或内部判定细节。
- 不区分「资源不存在」与「存在但无权」（防枚举）；`401`（认证失败）与 `403`（授权失败）语义不混用。

## 5. 拒绝审计契约

- 每次拒绝恰好一条 `authz_events`：主体类别 + 主体标识 + 动作 + 资源类型/标识 + 理由 + 方法/路径 + 时间（有 traceId 则带上）；**API Key 只记名称，绝不含明文**。
- **放行不落库**（零写放大）；本表追加型，不提供更新/删除路径。
- 写入失败只记 ERROR 日志，**绝不把拒绝变成放行**；失败不影响后续请求。
- 认证事件（登录成功/失败/登出/Key 使用）不在本表，归 #461。

## 6. 兼容性承诺

- `/admin/**`：注册模式、登录页与 `/admin/assets/**` 放行、session 有效即通过（不查 `web_users.enabled`）、浏览器 302 / curl 401 全部与 012 验收时一致。
- `/api/v1/**`：018 的豁免清单（`OPTIONS`、`/api/v1/health`、`/actuator/health*`、`/api/v1/auth/**`）逐条不变；401 响应体与 `WWW-Authenticate: Bearer realm="OryxOS"` 一字不改。
- `PROTECTED_URL_PATTERNS` / `/admin/*` 两个注册数组零改动；**不新增任何 URL pattern**；不引入 Spring Security filter chain（维持 `spring-security-crypto` 单 jar 边界）。
- `tool_policy_rules` 与 020 的语义零变化；本契约的 `Action` 与工具策略（主体=Agent 名）正交、叠加生效、互不豁免。
- `tool_invocations` / `llm_calls` 的列与写入口径零变化；`web_sessions`、`api_keys`、`sessions` 表零改动；`web_users` 只加一列 `roles`（NOT NULL DEFAULT `VIEWER`，旧行自动补齐）。
- 运行时：本刀不改 `AgentService.process*` 签名、不改 `ReActLoop` / `ToolExecutor`；接口与值对象在 `oryxos-core`，运行时将来复用无需依赖 `oryxos-web`。
- 管理台：新增 `/api/v1/auth/me` 的 `roles` / `rbacEnabled` 两个返回字段（追加式，旧前端忽略即可）；不改既有页面与按钮行为（按钮级隐藏不在本刀）。
