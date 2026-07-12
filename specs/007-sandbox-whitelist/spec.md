# Feature Specification: Sandbox 白名单——把工具执行前那道安全校验的墙真正砌起来

**Feature Branch**: `class-24`

**Created**: 2026-07-12

**Status**: Draft

**Input**: User description: "第24节需求：Sandbox 白名单实现——把工具执行前那道安全校验的墙真正砌起来。核心阶段做应用层白名单校验，接口先行、能撑住未来容器/microVM。前面几节已经把 Sandbox 接口/动作值对象/动作类型/违规异常立好，并在文件/命令/HTTP 三类内置工具的执行首行接入了校验调用，当前挂的是放行一切只记告警的临时实现。这一节把临时实现换成真正的白名单实现，并把还只留了注释位的通知工具也接上校验。"

## Clarifications

### Session 2026-07-12

答案来源：第24节课件 §3.2 `matchesDomain`（`host.endsWith(pattern.substring(1))` + `host.equals(pattern)` 双分支），非提问所得。

- Q: 通配符 `*.example.com` 是否匹配多级子域 `a.b.example.com`？ → A: 匹配。语义为"以 `.example.com` 结尾即命中"，多级子域天然满足。
- Q: 裸域 `example.com` 是否算命中 `*.example.com`？ → A: 不命中。`example.com` 不以 `.example.com` 结尾；裸域要放行须单独配非通配的精确项（走 `host.equals(pattern)` 分支）。
- Q: 非通配配置项如何匹配？ → A: 精确相等匹配（`host.equals(pattern)`），大小写按域名规范化后不敏感。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 越界动作在发生前被拦下且审计可读 (Priority: P1)

运营方给某 Agent 配好文件路径、命令、HTTP 域名三类白名单。当 Agent 被提示注入诱导去读白名单外的敏感文件、跑白名单外的危险命令、或请求白名单外的域名时，动作在真正执行（真正读文件/起进程/发请求）之前就被拦下、根本不发生；这次失败以"违规原因可读"的形式落进审计记录，运营方事后能看懂"因为什么被拦"。

**Why this priority**: 这是整个 Sandbox 特性的存在理由——从"只记告警放行一切"变成"真正拦截"。没有它，安全校验形同虚设。它单独实现即可交付核心价值：危险动作真的做不成。

**Independent Test**: 配一份只允许某目录/某命令/某域名的白名单，分别喂一个越界的文件读、命令、请求，断言：动作被拒（抛既有违规异常）、底层执行器从未被触达（IO 零发生）、审计记录 success=false 且错误信息是违规原因。

**Acceptance Scenarios**:

1. **Given** 文件白名单只含 `/data/agent`，**When** Agent 尝试读 `/etc/passwd`，**Then** 动作在读之前被拒、抛违规异常、审计落 success=false 带违规原因。
2. **Given** 命令白名单只含 `ls`、`cat`，**When** Agent 尝试跑 `rm -rf /`，**Then** 动作在起进程之前被拒、审计留痕。
3. **Given** 域名白名单只含 `*.example.com`，**When** Agent 尝试请求 `http://evil.com/x`，**Then** 请求在发出之前被拒、审计留痕。

---

### User Story 2 - 白名单内的正常调用照常放行 (Priority: P1)

Agent 正常调用白名单范围内的文件、命令、请求时，校验放行，工具和以前一样跑通，行为无回归。安全的墙不能把正常业务也挡在门外。

**Why this priority**: 与 US1 同等关键——一个只会拒绝的墙没有可用性。放行路径必须证明零回归，否则整套工具体系不可用。

**Independent Test**: 配白名单后，分别喂一个白名单内的文件读/命令/请求，断言校验通过、底层执行器被正常调用、结果与无沙箱时一致。

**Acceptance Scenarios**:

1. **Given** 文件白名单含 `/data/agent`，**When** Agent 读 `/data/agent/report.txt`，**Then** 放行、正常返回文件内容。
2. **Given** 命令白名单含 `ls`，**When** Agent 跑 `ls -la`，**Then** 放行、正常返回目录列表。
3. **Given** 域名白名单含 `*.example.com`，**When** Agent 请求 `https://api.example.com/v1`，**Then** 放行、正常发出请求。

---

### User Story 3 - 绕过手法（路径穿越 / 形似域名）被挡住 (Priority: P1)

攻击者不直接越界，而是用技巧绕过白名单：用相对路径穿越（`../` 爬出允许目录）伪装成允许路径，或用后缀相同但并非真子域的形似域名（如 `evil-example.com` 之于 `*.example.com`）冒充白名单域名。两种绕过都必须被识破并拦住。

**Why this priority**: 这是白名单校验最容易被攻破的两个点，也是本节 harness 的关键回归。放行/拒绝的正确性若不覆盖绕过手法，白名单等于纸糊的。

**Independent Test**: 路径穿越回归——白名单含 `/data/agent`，喂 `/data/agent/../../etc/passwd`，断言标准化后识别为越界并拒绝。形似域名回归——白名单含 `*.example.com`，喂 `evil-example.com`，断言点号边界不被绕过、拒绝。

**Acceptance Scenarios**:

1. **Given** 文件白名单含 `/data/agent`，**When** 输入 `/data/agent/../../etc/passwd`，**Then** 路径标准化后判定越界、拒绝。
2. **Given** 域名白名单含 `*.example.com`，**When** 输入主机名 `evil-example.com`，**Then** 通配符按点号边界匹配、判定非子域、拒绝。
3. **Given** 域名白名单含 `*.example.com`，**When** 输入主机名 `example.com`（裸域），**Then** 判定不命中、拒绝（裸域不以 `.example.com` 结尾）。

---

### User Story 4 - 通知工具的对外推送也过校验 (Priority: P2)

通知工具（webhook/企业微信/飞书/钉钉推送）在此前只留了一个"校验接线位"的注释。本节把它真正接上：通知推送在发出之前先过 HTTP 域名校验，推送目标域名不在白名单内则被拦下。让"所有对外 IO 都过沙箱"这条不变量在通知工具上也成立。

**Why this priority**: 补齐最后一个对外 IO 缺口，但通知工具本身是既有交付物、改造点范围小，故次于三类核心工具的校验落地。

**Independent Test**: 给通知工具装配一个只允许某域名的白名单，触发一次推送到白名单外域名，断言推送在发出前被拒、底层 HTTP 从未发出。

**Acceptance Scenarios**:

1. **Given** 域名白名单只含 `*.example.com`，**When** 通知工具向 `https://hooks.evil.com/xxx` 推送，**Then** 推送在发出前被域名校验拦下。
2. **Given** 域名白名单含 `*.feishu.cn`，**When** 通知工具向合法飞书 webhook 推送，**Then** 校验放行、正常发出。

---

### Edge Cases

- **空白名单语义**：某类白名单配置为空时，MUST 语义为"什么都不允许"（deny-all），绝不能退化为"不校验/全放行"。这是安全默认值，配置缺失不得静默裸奔。
- **路径符号链接 / 多重 `..`**：标准化 MUST 处理多重 `..` 和冗余分隔符；符号链接解析深度不在本节范围（记为假设）。
- **命令带前后空格 / 多空格分隔**：取"首个词"时 MUST 容忍前导空格与多空格。
- **URL 无主机名 / 畸形 URL**：无法解析出主机名的 HTTP 目标 MUST 判为不通过（不能因解析失败而放行）。
- **通配符匹配多级**：`*.example.com` 匹配 `a.b.example.com`（以 `.example.com` 结尾即命中，见 Clarifications）；点号边界（不匹配 `evil-example.com`、不匹配裸域 `example.com`）是硬回归。
- **大小写**：域名比对 MUST 大小写不敏感（`API.Example.COM` 命中 `*.example.com`）。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供一个白名单校验实现，接收工具执行前上报的"动作"（含动作类型与目标），按动作类型路由到三类校验：文件路径、命令、HTTP 域名。任一类校验不通过 MUST 抛出既有的违规异常，使该动作零发生。
- **FR-002**: 文件路径校验 MUST 先对目标路径做标准化（消解 `..`、冗余分隔符、转绝对路径）再与允许根目录比对；标准化后不落在任一允许根目录之内即拒绝，据此挡住相对路径穿越。
- **FR-003**: 命令校验 MUST 取命令字符串的首个词（可执行名）与允许命令清单精确比对；首词不在清单即拒绝。
- **FR-004**: HTTP 域名校验 MUST 从目标 URL 解析出主机名后做通配符匹配；通配符 MUST 带点号边界——`*.example.com` 只匹配其真子域，MUST NOT 被后缀相同的形似域名（如 `evil-example.com`）绕过。无法解析出主机名 MUST 判不通过。域名比对 MUST 大小写不敏感。
- **FR-005**: 三类白名单 MUST 来自配置（文件允许路径、命令允许清单、域名允许清单三块独立配置）。任一类配置为空 MUST 语义为"该类什么都不允许"，MUST NOT 退化为"不校验"。
- **FR-006**: 既有三个内置工具（文件、命令、HTTP）的执行首行校验调用已接好，本特性 MUST NOT 改动它们的调用点与调用签名。
- **FR-007**: 通知工具此前只留了注释接线位，本特性 MUST 在其对外推送前真正接上 HTTP 域名校验；为此通知工具的构造 MUST 增加对校验器的依赖（既有交付物预告的改造点）。
- **FR-008**: 校验失败 MUST 复用既有工具执行链的失败审计路径（写审计表 success=false、错误信息为违规原因），MUST NOT 为校验单独新增一条审计逻辑或审计表。
- **FR-009**: 运行时装配 MUST 把临时的"放行一切只记告警"实现替换为白名单实现，并装配三块白名单配置来源。
- **FR-010**: 本特性 MUST NOT 改动既有校验接口、动作值对象、动作类型枚举、违规异常的签名（均由前序节定死）；仅新增白名单实现类与配置载体，并按预告改造通知工具构造与运行时装配。

### Key Entities *(include if feature involves data)*

- **白名单校验器（实现）**：实现既有校验接口的一个具体类；持有三类白名单配置；对外只暴露"校验一个动作，不过则抛违规异常"的既有契约。
- **动作（既有值对象）**：由动作类型（文件读 / 文件写 / 命令 / HTTP 请求四值，前序节定死）+ 目标字符串组成；本特性只消费，不改其结构。
- **三块白名单配置**：文件允许路径列表、命令允许清单、域名允许清单；来自既有应用配置文件的三个配置键；空列表 = deny-all。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 配好白名单后，越界的文件读、命令、请求三类各自被拒且底层执行器零触达（IO 未发生）——由自动化 harness 断言 100% 覆盖。
- **SC-002**: 相对路径穿越（`../` 爬出允许目录）在标准化后 100% 被识别为越界并拒绝——关键回归点，harness 恒绿。
- **SC-003**: 后缀相同的形似域名（非真子域）0% 能绕过通配符白名单——关键回归点，harness 恒绿。
- **SC-004**: 白名单内的正常文件/命令/请求 100% 放行、行为与前序节零回归——前序节全部测试保持全绿。
- **SC-005**: 四个受管工具（文件、命令、HTTP、通知）各有一条"白名单外输入被拦且真正 IO 没有发生"的自动化断言全部通过。
- **SC-006**: 任一类白名单配置为空时，该类动作 100% 被拒（deny-all 语义），无一放行。

## Assumptions

- 校验接口、`SandboxAction` 值对象、`ActionType` 枚举（四值：文件读/文件写/命令/HTTP 请求）、违规异常均由第20节交付且签名定死，本特性不改；文件/命令/HTTP 三工具的执行首行校验调用已由第20节接好。
- 通知工具为第19节交付物，其构造在本节按预告增加校验器依赖——属于既有改造点，不算新增对外概念。
- 失败审计走第17节 `ToolExecutor` 既有的失败落库路径；通知工具若不经该路径，其校验失败以抛既有违规异常的方式让上层按既有约定处理。
- 三块白名单配置走既有应用配置文件的三个新配置键；凭证类信息不涉及，配置为纯路径/命令名/域名字符串。
- 边界（本节明确不做）：容器隔离、microVM、进程级/whole-process 隔离、把 MCP 子进程与代码执行也纳入校验、Profile 级 Tool Policy（allow/deny）——全部扩展阶段。
- 符号链接解析深度、chroot 逃逸等 OS 级绕过不在应用层白名单射程内，属未来容器/microVM 档职责。
