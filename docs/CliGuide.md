# OryxOS CLI 使用文档

OryxOS 打包为一个可执行 JAR，所有操作都通过 `oryxos` 命令的子命令完成：在终端里跟 Agent 对话、把服务跑起来、查配置和状态。本文覆盖核心阶段的 12 个子命令（第 18 节交付）。

> CLI 是消息进出的门，不是干活的人——它不想、不调模型、不执行工具，这些全在引擎（ReAct 循环）里。

---

## 1. 构建与运行

```bash
# 构建 fat jar（仓库根目录）
mvn -pl oryxos-boot -am package -DskipTests

# 运行（下文所有 oryxos 命令均指这个别名）
alias oryxos='java -jar /path/to/oryxos/oryxos-boot/target/oryxos-boot-*.jar'

oryxos --help      # 总览
oryxos --version   # 版本与 JVM/OS 信息
```

**运行目录约定**：CLI 默认在**当前目录**寻找 `.oryxos/` 工作区和 `oryxos.db` 数据库。请固定在同一个目录下运行各命令，否则 chat 写的会话、`session list` 会查不到。

**工作区根可自定义**：默认根是 `.oryxos`，可通过环境变量 `ORYXOS_ROOT` 或 JVM 系统属性 `-Doryxos.root=<path>` 覆盖。轻命令（init/status/profile）不启动 Spring、不读 yaml，只认这两者（解析顺序 `-Doryxos.root` → `ORYXOS_ROOT` → 默认 `.oryxos`）；重命令（serve/gateway）从 `application.yml` 的 `oryxos.root` 读取，Spring relaxed binding 同样把 `ORYXOS_ROOT` 绑上去，所以设一个环境变量两边都认。配置的根会自动加入文件沙箱白名单，换根不破坏文件工具。

```bash
ORYXOS_ROOT=/data/ws oryxos init      # 初始化到自定义工作区
ORYXOS_ROOT=/data/ws oryxos chat      # 重命令同样指向它
```

---

## 2. 快速开始（5 分钟）

```bash
mkdir my-agent && cd my-agent
oryxos init                        # ① 初始化工作区
oryxos profile create weather      # ② 创建一个 Agent
oryxos profile list                # ③ 确认它在
export DEEPSEEK_API_KEY=sk-xxx     # ④ 配置模型凭证（环境变量，绝不明文写文件）
oryxos chat --profile weather      # ⑤ 开聊
```

```text
已连接 Agent [weather]，输入 /quit 退出。
> 今天天气怎么样？
（Agent 回复……）
> /quit
再见。
```

隔天再进来，`oryxos chat --profile weather` 会**续用同一条会话**，上次聊过什么还在。

---

## 3. 命令总览

| 命令 | 类型 | 作用 |
|------|------|------|
| `oryxos init` | 轻 | 初始化 `.oryxos/` 工作区 |
| `oryxos status` | 轻 | 查看工作区与数据文件状态 |
| `oryxos chat [--profile <name>]` | **重** | 交互式对话（默认 profile：`default`） |
| `oryxos serve [--port 8080]` | **重** | 启动 HTTP API 服务（REST 端点 + Web 管理台） |
| `oryxos gateway` | **重** | 守护进程模式（多 Channel 挂载属扩展阶段） |
| `oryxos profile list` | 轻 | 列出全部 Profile |
| `oryxos profile create <name>` | 轻 | 创建 Profile（最小模板，不覆盖已有） |
| `oryxos profile show <name>` | 轻 | 查看 Profile 内容 |
| `oryxos profile delete <name>` | 轻 | 归档 Profile 到 `archive/`（不物理删除） |
| `oryxos provider list` | 轻 | 列出 Provider 注册表（数据库） |
| `oryxos tool list` | 轻 | 列出可用工具（20 节起为实时清单） |
| `oryxos session list` | 轻 | 列出会话概览 |
| `oryxos apikey add <name>` | 轻 | 生成 REST API Key（明文仅显示一次） |
| `oryxos apikey list` | 轻 | 列出 API Key（前缀/状态/最近使用，无明文） |
| `oryxos apikey revoke <name>` | 轻 | 吊销 API Key（即时生效） |
| `oryxos user list` | 轻 | 列出管理台账号（USERNAME / ENABLED / ROLE / CREATED_AT） |
| `oryxos user role <name> <VIEWER\|EDITOR\|ADMIN>` | 轻 | 设置账号角色（039 授权；新建账号默认 VIEWER） |

**轻/重的区别**：轻命令直接读写文件或只读查库，**不启动 Spring**、秒级返回（实测约 0.35s）；重命令要调模型、跑引擎，才付出 2~4 秒的完整运行时启动代价。判断标准就一条：这个命令要不要调模型/跑引擎。

所有命令都支持 `--help`；打错命令会得到统一报错和纠正建议（如 `Did you mean: oryxos session or oryxos serve?`），不会抛堆栈。

---

## 4. 逐命令说明

### 4.1 init——初始化工作区

```bash
oryxos init
```

在当前目录创建：

```text
.oryxos/
├── agents/      # 每个子目录 = 一个 Agent（AGENT.md + 可选 skills/ scripts/ REFERENCE.md）
├── memory/      # 全局长期记忆（每个 Agent 自己的 MEMORY.md 在 agents/<name>/ 下）
├── sessions/    # 备用（会话已入 SQLite）
├── logs/
├── AGENTS.md    # Bootstrap：项目级 agent 行为说明
├── SOUL.md      # Bootstrap：agent 人格定义
└── USER.md      # Bootstrap：用户偏好（只读）
```

`oryxos.db` 由首次运行的重命令创建，`init` 本身不建库。

**幂等**：重复运行不覆盖任何已有文件，放心多敲。

### 4.2 status——看一眼状态

```bash
oryxos status
```

输出工作区是否初始化、Profile/Skill 数量、数据库指向（SQLite 显示文件是否已创建；外部 PostgreSQL 显示连接指向）。排查"为什么 chat 不认我的 Agent"先看这里。

### 4.3 chat——交互式对话（核心命令）

```bash
oryxos chat                    # 用 default Agent
oryxos chat --profile weather  # 用指定 Agent
```

- 每行输入交给 ReAct 引擎处理，回复打印到终端；
- **打字机输出（019）**：回复逐段实时打印而非静默等待后整段吐出；工具调用期间有 `[调用工具 xx …]` / `[工具 xx 完成]` 单行状态提示；Provider 不支持流式时自动回落为整段输出，无需任何配置；
- **`/quit` 退出**（前后空白不影响）；Ctrl-D（EOF）等同退出；空行自动跳过；
- 会话身份 = `渠道:用户:Agent` 三元组（渠道固定 `cli`，用户取系统用户名）。同一身份**永远续用同一条会话**，跨重启历史不丢；
- 前置条件：Profile 存在、对应 Provider 的环境变量已配置（见 §5），否则启动即点名报错、不进入对话。

### 4.4 serve / gateway——常驻模式

```bash
oryxos serve --port 8080   # REST API（/api/v1）+ Web 管理台（/admin/）
oryxos gateway             # 守护进程（多 Channel 挂载属扩展阶段）
```

`serve` 除了对外暴露 `/api/v1` 下的 REST 端点，还在 `http://<host>:<port>/admin/` 提供 Web 管理台。serve/gateway 是重命令，从 `application.yml` 的 `oryxos.root` 读取工作区根（也认 `ORYXOS_ROOT`）。

三种运行模式（chat/serve/gateway）**共享同一份 Profile 配置和同一套会话存储**，差异只是消息从哪进来。Ctrl-C 退出。

### 4.5 profile 四件——Agent 管理

```bash
oryxos profile create ops-agent   # 生成最小模板到 .oryxos/agents/ops-agent/AGENT.md
oryxos profile show ops-agent     # 打印 AGENT.md 内容
oryxos profile list               # 列出全部（每行一个 Agent 目录名）
oryxos profile delete ops-agent   # 归档整个 Agent 目录到 .oryxos/archive/（不物理删除；不存在则报错点名）
```

**一个目录 = 一个 Agent**：每个 Agent 是 `.oryxos/agents/<name>/` 下的一个目录，核心是 `AGENT.md`——YAML frontmatter（这个 Agent 的 profile：identity/provider/tools/bootstrap/settings）+ 正文（任务指令），外加可选的 `skills/`、`scripts/`、`REFERENCE.md`。**不再有 `.oryxos/profiles/` 目录**。create 生成的模板直接编辑即可定制——**改配置就是改 Agent，不需要写代码**。改完无需重启：下一轮对话即生效（上下文文件每次组装都重新读取）。

### 4.6 provider list / tool list / session list——三张清单

```bash
oryxos provider list   # Provider 注册表（数据库；name / default / base-url / description）
oryxos tool list       # 可用工具清单（20 节 ToolRegistry 就位后为实时注册表）
oryxos session list    # 会话概览：session_id / profile / status / last_active_at
```

`provider list` 与 `session list` 同一条通道：直连当前目录的 `oryxos.db`（或 `config/application.yml` 指向的外部库）只读查询 `providers` 表；打包 YAML 只在服务首次启动时播种，之后数据库是唯一事实源。
`session list` 直连当前目录的 `oryxos.db` 只读查询；库还没创建时提示"暂无会话"。

---

### 4.7 apikey 三件——REST API Key 管理（018）

`oryxos.web.apikey.enabled=true` 时 `/api/v1/**` 启用机器调用认证（豁免 `/api/v1/health`、`/api/v1/auth/*`、OPTIONS 预检；`/admin/**` 不受影响）。Key 由这组命令管理：

```bash
oryxos apikey add ci-bot     # 生成 Key；明文只显示这一次，库中仅存 SHA-256 哈希
oryxos apikey list           # NAME / PREFIX / STATUS / CREATED_AT / LAST_USED_AT（无明文）
oryxos apikey revoke ci-bot  # 吊销，下一次请求即 401；其它 Key 不受影响
```

调用方任选一种请求头携带：

```bash
curl -H "Authorization: Bearer oryx_..." http://localhost:8080/api/v1/profiles
curl -H "X-API-Key: oryx_..." http://localhost:8080/api/v1/profiles
```

注意：`add` 重名会报错不覆盖；`revoke` 已吊销的 Key 幂等提示；明文丢了只能吊销重发（无法找回）。建议与管理台认证（`oryxos.web.auth.enabled`）同时开启，否则管理台数据页无凭据可用（启动时会告警提示）。

---

### 4.8 工具策略（020）——管理台治理入口

工具级 allow/deny 治理不走 CLI，入口在管理台「OS 运行时 → 工具策略」页（或 REST `/api/v1/tool-policy`）：全局禁用某工具、给指定 Agent 登记例外或定向收紧，变更即刻热更新生效。策略与沙箱白名单正交（策略管"能不能用工具"，沙箱管"工具能碰什么资源"）；被策略拒绝的调用在终端表现为 `[工具 xx 失败]` + 模型解释，审计里带 `blocked_by='policy'` 标记可筛。

---

### 4.9 审计 Trace（021）——报障定位与全链路回放

每次消息处理生成唯一 trace ID：REST 响应体（`data.traceId`）、SSE 流首 `trace` 事件、执行历史行里都能拿到。用户报障时报上这个 ID，管理员在管理台「报表」页输入即可回放本轮完整链路（每次 LLM 调用与工具执行的时间序、耗时、token 与成本合计），或直接查 `GET /api/v1/audit/trace/{traceId}`；服务日志里同一 ID 经 MDC 贯穿，`grep <traceId>` 可与审计互查。时间线里的工具参数/结果摘要经内置规则脱敏（API key、口令类字段掩码），库中保留原文供特权排障。

### 4.10 Provider 失败切换与监控指标（023）

Agent 的 `AGENT.md` provider 节可声明 `fallback:` 有序备用列表（每项 name+model，引用已注册 Provider）——主 Provider 网络故障/超时/限流/凭证失效时该次调用自动按序切换备用，终端无感知；每次尝试都留审计、切换有 WARN 日志（带 traceId 可互查）。运维将 `/actuator/prometheus` 接入企业 Prometheus/Grafana 即可看到 `oryxos_` 前缀业务指标（LLM 调用/耗时/token/工具/策略拦截/切换计数）并配置告警（如「fallback 切换次数突增」）。

---

### 4.11 数据库选型（025）——SQLite 默认档与 PostgreSQL 部署选项

- **默认零配置**：什么都不配就是单机 SQLite（`oryxos.db` 相对启动目录），行为与历史版本一致；升级后首次启动自动把表结构接管进 Flyway 迁移管理（`flyway_schema_history` 表），数据无损、重启不重复。
- **切 PostgreSQL**（多副本/高并发写，PG 14+）：编辑 `config/application.yml` 的 `spring.datasource`——只需 `url`（`jdbc:postgresql://主机:5432/库名`）+ `username` + `password: ${ORYXOS_DB_PASSWORD}`（凭证走环境变量）。库类型按 url 自动识别，驱动/方言/迁移脚本自动切换，无需配置其他任何项。
- **报错口径**：连接不上、认证失败、权限不足、迁移失败四类各自报错可区分且拒绝启动，不会静默退回 SQLite。
- 轻命令（`status` / `session list` / `knowledge list`）与服务读同一份配置，两种库同口径工作。

### 4.12 多副本部署（026）——两副本起步的正确性保障

- **开关**：`oryxos.cluster.enabled=true`（默认 false=单机档一切现状）；每副本配唯一 `oryxos.cluster.instance-id`；数据库必须是共享 PostgreSQL（025 档）。
- **用户可感知的保证**：同一会话消息按序恰好一答（跨副本排队与单机体验一致）；平台重推/用户重发只答一次；定时任务恰好执行一次；某副本崩溃后该轮标失败、下一条消息由健康副本接管（不自动重放，重发即恢复）；企微连接自动接管不互踢。
- **运维**：`GET /api/v1/instances` 看副本存活与"谁在处理什么"；`oryxos_leases_*` / `oryxos_fence_conflicts_total` / `oryxos_duplicates_dropped_total` 指标可告警。
- **误配拒启**：cluster 开着但配了 SQLite / markdown 记忆档 / memory 知识库 → 启动失败并指明改法（带病运行比失败更危险）。
- 参数（有安全默认，一般不用动）：`lease-ttl` 30s / `heartbeat-interval` TTL/3 / `poll-interval` 500ms / `wait-timeout` 120s / `workspace-poll-interval` 1s（027）。
- **文件面（027）**：`.oryxos/` 工作区放共享卷（NFS / K8s RWX PVC，支持矩阵见 `docs/SharedVolumeGuide.md`）——任一副本上建/改/删 Agent、Skill、人格，其余副本 ≤3s 生效（DB 版本号总线，集群档不再依赖 inotify）；知识索引重建跨副本恰好一次（认领冲突返回 409「构建进行中」，执行副本崩溃 30s 后可接管）；运维直接改盘后调 `POST /api/v1/workspace/refresh` 触发全副本重载。
- **K8s 一条命令部署（039）**：`helm install oryxos charts/oryxos --set database.existingSecret=… --set masterKey.existingSecret=…`——双副本默认档、滚动升级零失败（就绪门控 + preStop 优雅期 + 停机即释放渠道属主租约）、可选 `otel.endpoint` 把每轮 trace 接进 Jaeger/Tempo（与 `/api/v1/audit/trace/{id}` 同源互查）。安装/升级/排查见 `docs/K8sDeployGuide.md`；裸机与 compose 形态零变化（`bin/stop.sh` 宽限统一为 40s，对齐实测优雅停机口径）。

### 4.13 账号与角色（012 / 039）——管理台账号与授权三档

管理台账号（012 认证）与资源授权（039）的账号面。只读写库、不调模型；`user list` **绝不打印密码或哈希**。

```bash
oryxos user add admin                      # 交互输两遍密码（≥8 字符）；新建账号默认 VIEWER
oryxos user list                           # USERNAME / ENABLED / ROLE / CREATED_AT
oryxos user role admin ADMIN               # 设角色：VIEWER | EDITOR | ADMIN（大小写不敏感）
oryxos user passwd admin                   # 改密码（交互输新密码）
oryxos user disable admin                  # 禁用：保留行，登录即 401；enable 反向
oryxos user enable admin
oryxos user delete admin
oryxos user oidc-map admin <issuer> <sub>  # 040：把 OIDC 外源主体映射到本地账号
```

- **`user role` 每次设一个角色**：覆盖式（不是追加），非法角色名清晰报错并给非零退出码。角色每请求从库重解析、不缓存——**改档或撤权在下一次请求即生效**，无需重启。
- **`user list` 的 ROLE 列**：一账号多角色时以逗号分隔；角色字段为空显示 `-`。
- **三档能做什么**（逐级包含，定义在 `AuthorizationService` 的唯一决策点，不在 Filter/Controller 里比角色）：
  - `VIEWER` —— 只读：看工作区与审计。
  - `EDITOR` —— 在 VIEWER 之上可干活：跑 Agent，管 Agent / 知识库 / Skill / 自己的会话。
  - `ADMIN` —— 在 EDITOR 之上管边界：成员、渠道、策略、工作区设置。
- **API Key 主体另有硬上限**：即使给它 `ADMIN`，也不得改成员与策略（Key 是长期有效、可复制到任意环境的机器凭证，不与人账号同权限上限）。
- **开启授权**（`oryxos.web.rbac.enabled=true`，默认 false）后启动做两项校验，**误配即拒启并指路**：apikey 认证未开（没有门产出主体，授权会静默失效）、或库中没有任何 ADMIN 账号（治理面会锁死，报错直接提示 `oryxos user role <name> ADMIN`）→ 均拒绝启动；`oryxos.web.auth.enabled=false` 只告警（管理台数据页将无 session 可用）。开关关系是**认证开 → 授权才有对象**。
- **拒绝留痕**：越权请求返回 403（与认证失败的 401 区分），结构化 WARN 入日志，并落 `authz_events` 表可筛；未登记的受保护路径 fail-closed 拒绝。外部回调面（渠道入站、审批回调等）与健康检查、`/api/v1/auth/**` 只认证不裁决。

面向使用者的授权说明见网站文档「认证 → 授权（RBAC）」一节。

## 5. 配置与凭证

### 5.0 主密钥（022）

管理台/API 录入的第三方凭证（Provider API key、SMTP 密码等敏感项）落库前经主密钥 AES-256-GCM 加密（`enc:v1:` 前缀）。单独拿到数据库文件不等于拿到凭证。

- **本地试用**：无需配置；首次启动自动生成 `.oryxos/master.key`，权限仅属主可读（0600）。
- **生产部署**：设置环境变量 `ORYXOS_MASTER_KEY`，值为 Base64 编码的 32 字节密钥，可用 `openssl rand -base64 32` 生成；Kubernetes 从 Secret 注入。环境变量优先于本地密钥文件。

生产环境应安全备份并保持主密钥不变。密钥丢失或更换后，已有凭证将无法解密；需恢复原密钥，或删除并重新录入相关凭证。本地密钥文件只防止数据库文件单独外流，不防整机沦陷。升级后首次启动会将存量明文记录迁移为密文。

### 5.1 Provider 播种与运行时数据源

仓库中的 `config/application.yml.example` 默认使用内置 `mock` Provider，本地启动无需 LLM key。如需在首次启动时播种外部 Provider，在 `oryxos.providers` 中使用环境变量占位符，并在启动前导出环境变量：

```yaml
oryxos:
  providers:
    - name: deepseek
      api-key: ${DEEPSEEK_API_KEY:}
      base-url: https://api.deepseek.com
```

```bash
export DEEPSEEK_API_KEY=your-key-here
```

YAML 只会播种数据库中尚不存在的 Provider。播种后，运行时以数据库中的加密 Provider 注册表为准；修改 YAML 或环境变量不会覆盖或轮换已存在 Provider。请通过管理台或 `PUT /api/v1/providers/{name}` 新增 Provider 或轮换凭证。Provider key 落库加密，API 只返回掩码值。管理端点暴露到可信网络之外前，必须配置认证和网络访问控制。

---

## 6. 会话机制（为什么"聊过的都记得"）

- 会话身份由三元组 `渠道:用户:Agent` 唯一决定，拼接只发生在系统内部一处——CLI 进来的是 `cli:<你的系统用户名>:<profile>`，Web 进来是 `web:...`，互不串扰；
- 对话历史（用户消息 / 模型响应 / 工具结果）按序累积，整体存入 SQLite 的 `sessions` 表；
- 进程重启、换运行模式，同一三元组进来都能拿回完整历史；
- 每次调模型只带最近 N 轮历史（Profile 的 `max_history_turns`，默认 20），上下文不会无限膨胀。

---

## 7. 常见问题

| 现象 | 原因与处理 |
|------|-----------|
| chat 启动提示 api-key 未配置或环境变量未解析 | 首次 YAML 播种时设置对应环境变量；如 Provider 已在数据库中管理，则通过管理台/API 配置。 |
| chat 报 Profile 不存在 | `oryxos profile list` 确认名字；没有就 `profile create`；注意运行目录是否对 |
| `session list` 显示暂无会话，但明明聊过 | 换了运行目录——`oryxos.db` 在当前目录下，回到当初跑 chat 的目录 |
| 轻命令也很慢 | 确认跑的不是 chat/serve/gateway；轻命令不启动 Spring，正常应亚秒返回 |
| 启动日志想确认存储装配 | chat 启动日志应有 `Found 3 JPA repository interfaces`（0 说明装配残缺，属 bug） |
| 想换模型 | 改 Profile 的 `provider.model` 字段即可，无需改代码、无需重启 |

---

## 8. 能力边界（核心阶段）

- IM Channel（企业微信/飞书/钉钉/Slack）：扩展阶段；
- `serve` 的 REST 端点与 `/admin/` Web 管理台已交付（详见 REST API 文档）；
- 定时触发（"钟推"）、工具真实执行（文件/Shell/HTTP）均已在核心阶段接入。

---

*对应实现：第 18 节《CLI：功能概述、实现思路与代码讲解》；技术细节见 `docs/TechnicalSolution.md` §8.4/§8.6/§8.7/§9.2。*
