# Feature Specification: Notify——结果主动送出去的统一出口

**Feature Branch**: `class-19`（用户指定，勿另建）

**Created**: 2026-07-11

**Status**: Draft

**Input**: User description: "第19节需求：Notify——结果主动送出去的统一出口。……（完整需求见第19节课件《Notify 模块 原理解析、实现与代码讲解》一、二部分）"

## Clarifications

### Session 2026-07-11

- Q: notify 的 channel 参数按什么匹配渠道？ → A: 按渠道类型（NotifyChannel.type）匹配——16 节已定的 NotifyChannel 只有 type + config 两个字段，无名字可匹配；channel 为空、空白或字面量 "default"（课件示例用词）时取第一个渠道；指定类型匹配不到时报错点名、不回退（默认自答，停点供确认）。
- Q: 对端 3xx/4xx 是否与 5xx 同口径？ → A: 同口径——凡非成功响应都异常上抛；"发出去没送到"与"没发出去"对 Agent 是同一件事。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 定时结果主动送达 (Priority: P1)

用户对 Agent 说"每天早上帮我看看天气，穿搭建议直接发到我们群里"。之后每天到点自动查天气、生成建议，并把结果推送到该 Agent 配置好的群——没有人在另一端等响应，也不需要任何人工干预。

**Why this priority**: 这是 Notify 存在的全部理由：定时触发的结果若送不出去，跑完一整套循环也只能烂在会话里没人看到；25 节定时模块和 31 节两个 Demo 都指着这个出口。

**Independent Test**: 用本地假 webhook（单测层 HTTP 假服务）承接推送，断言收到一次 POST、body 含推送内容、目标地址来自该 Agent 的通知配置而非硬编码。

**Acceptance Scenarios**:

1. **Given** Agent 配置了一个 webhook 通知渠道，**When** 通知能力被调用（content="今天 28°C，建议短袖"），**Then** 目标地址收到一次 POST，body 中携带该内容。
2. **Given** 同上，**When** 检查发出的目标地址，**Then** 地址来自 Agent 配置（改配置即改目标），实现中无硬编码地址。

---

### User Story 2 - 发送失败不许装成功 (Priority: P1)

对端 webhook 返回错误（如 5xx）时，通知能力必须把失败显式抛出——Agent 不能以为发出去了，事后审计也必须能看到这次失败。

**Why this priority**: 静默吞掉发送失败是最危险的软故障：日报"发了"但群里没人收到，发现时已经断了一周。

**Independent Test**: 假 webhook 返回 500，断言发送调用异常上抛（不被吞掉）。

**Acceptance Scenarios**:

1. **Given** 假 webhook 固定返回 500，**When** 发送一条通知，**Then** 调用以异常结束、错误信息可见；该失败经工具执行统一路径落 tool_invocations（success=false）。

---

### User Story 3 - notify 工具的渠道解析 (Priority: P2)

Agent 在对话里调用 notify 工具：大多数时候只传 content——系统取该 Agent 配置的第一个通知渠道；显式传 channel 时按类型选对应渠道；该 Agent 压根没配 notify_channels 时明确报错，绝不静默失败。

**Why this priority**: 渠道解析是 notify 工具的业务核心；"未配置却装作发送成功"与 US2 是同一类必须钉死的软故障。

**Independent Test**: 构造带/不带 notify_channels 的 Profile 置入当前 Agent 上下文，分别断言：缺省取第一个渠道、显式选中指定渠道、未配置时报错点名。

**Acceptance Scenarios**:

1. **Given** 当前 Agent 配置了两个通知渠道，**When** 只传 content 调用 notify，**Then** 内容送往第一个渠道。
2. **Given** 同上，**When** 传 channel 指定第二个渠道类型，**Then** 内容送往第二个渠道。
3. **Given** 当前 Agent 未配置 notify_channels，**When** 调用 notify，**Then** 返回明确错误（点名未配置），不发出任何请求。

---

### User Story 4 - 换渠道零代码 (Priority: P3)

运营方想把通知从 A 群换到 B 群：只改该 Agent 配置里的 webhook 地址（或对应环境变量），不碰任何代码、不改对话内容、不重启。

**Why this priority**: "配置即 Agent"总原则在出站方向的体现；接口先行保证以后加新渠道（企业微信/飞书专用 API）只增实现不改调用方。

**Independent Test**: 同一套代码、两份不同 url 的通知目标，分别发送后断言各自地址收到——地址完全由配置决定。

**Acceptance Scenarios**:

1. **Given** 通知目标 config 中 url=A，**When** 发送，**Then** A 收到；**Given** 改为 url=B，**When** 再发送，**Then** B 收到（代码零改动）。
2. **Given** 抽象接口既定，**When** 未来新增某专用渠道实现，**Then** 接口签名与既有调用方不需要改（接口语汇不含任何一档实现特有的词——设计自查项）。

---

### Edge Cases

- 通知目标 config 缺 url 键（webhook 档必需）→ 发送前明确报错，不发出请求。
- content 为空串 → 照常发送（是否有意义由调用方/模型决定，通知层不猜）。
- channel 参数指定的类型在 notify_channels 里不存在 → 明确报错点名，不回退默认渠道（回退会把消息发错地方）。
- 对端返回 3xx/4xx → 与 5xx 同口径：异常上抛（凡非成功都不许装成功）。
- 当前 Agent 上下文缺失（工具在处理链路外被调用）→ 明确报错，不发送。
- 白名单检查位：Sandbox 就位前不拦截（留调用位），就位后校验先于发送——顺序回归留待 24 节 InOrder 钉死。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供不携带具体渠道细节的出站通知抽象：表达"把一条内容送到某个通知目标"；通知目标只含渠道类型与配置映射（配置如何解释由实现决定）；接口语汇 MUST NOT 出现任何一档实现特有的词。
- **FR-002**: 核心阶段 MUST 且仅 MUST 提供一档通用 webhook 实现：把内容包成 JSON 向目标地址发一次 POST（同步阻塞）；对端非成功响应 MUST 异常上抛，MUST NOT 静默吞掉；目标地址 MUST 取自通知目标配置，实现内不得硬编码。
- **FR-003**: 系统 MUST 提供 notify 内置工具能力：content 必传；channel 可选——缺省取当前 Agent notify_channels 的第一个渠道，显式传入时按渠道类型匹配、匹配不到报错点名；notify_channels 未配置或当前 Agent 上下文缺失 MUST 明确报错且不发出请求。
- **FR-004**: 通知发出前 MUST 过域名白名单检查位（与 http_post 共享同一份白名单与校验机制；校验实现属第 23/24 节，本特性留接线位）；执行与审计 MUST 走工具执行既有统一路径（tool_invocations），不新增审计逻辑。
- **FR-005**: 渠道配置（webhook 地址等）MUST 只存在于 Profile 的 notify_channels 字段；MUST NOT 出现在对话内容或工具接口签名中；配置中的凭证走环境变量占位（16 节机制）。

### Key Entities

- **通知目标（NotifyTarget）**: 渠道类型 + 配置映射；对上层是黑盒，由具体实现解释（webhook 档取 config 的 url）。
- **出站通知抽象（NotifyChannelAdapter）**: "送内容到目标"这一意图的唯一表达；核心阶段一档 webhook 实现，扩展阶段按档位增挂。
- **notify 工具（NotifyTools）**: LLM 可调用的内置工具骨架；渠道解析（Profile.notify_channels ←当前 Agent 上下文）+ 检查位 + 委托发送；@Tool 注册挂载归 20 节、白名单真实现归 23/24 节。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 配好通知渠道后，一次通知调用使目标地址收到恰好一次 POST 且内容完整——自动化断言（假 webhook）。
- **SC-002**: 发送失败（对端非成功响应）100% 以显式错误结束并留下审计记录，零静默失败。
- **SC-003**: 换通知目标只改配置零代码：同一实现对不同配置发往不同地址——自动化断言。
- **SC-004**: 未配置渠道 / 指定渠道不存在 / 上下文缺失三种情况 100% 明确报错且零请求发出。
- **SC-005**: 接口中立性：新增一档专用渠道实现无需修改抽象签名与调用方（设计自查项，人工确认）。

## Assumptions

- 第 16 节 Profile 的 `notify_channels` 字段（type + 渠道特定 config 如 url）与 `${ENV}` 占位解析已交付（含测试覆盖），本特性零配置改动。
- 第 17 节 ProfileContext（ThreadLocal）提供"当前是哪个 Agent"；notify 工具经由第 17 节 ToolExecutor 的统一执行与审计路径运行。
- HTTP 客户端使用项目锁定 BOM 内组件；发送同步阻塞（宪法同步模型）。
- 课件"实现顺序说明"（授课顺序 ≠ 构建顺序）：本特性交付抽象接口、通知目标、webhook 实现与 notify 工具骨架及第一批 harness；@Tool 注册挂载属第 20 节、白名单校验真实现与 InOrder 顺序回归属第 23/24 节，27/28 节串联全量验证。
- 明确不做（边界）：企业微信/飞书/钉钉专用 API（签名算法、AccessToken 刷新）留扩展阶段；入站 Channel 与出站 Notify 不合并抽象。
