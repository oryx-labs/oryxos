# Feature Specification: Provider——对接大模型的统一入口（第16节）

**Feature Branch**: `class-16`

**Created**: 2026-07-09

**Status**: Draft

**Input**: User description: "第16节需求：Provider——对接大模型的统一入口。多家模型并存、模型可随时换、每次调用可审计；Profile 声明模型选择；实例级 provider 清单与凭证；按名路由调用；只翻译工具不执行；成败都落审计；凭证走环境变量。边界：不做 ReAct/工具执行/fallback/成本看板/流式。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 多 Provider 并存，按 Agent 配置精确路由 (Priority: P1)

企业在同一个 OryxOS 实例上跑多个 Agent：运维 Agent 用 deepseek、客服 Agent 用 qwen。每个 Agent 的一次模型调用，必须严格路由到它自己 Profile 里声明的那家 provider 和那个 model，拿回响应原样返回给上层；请求里可以附带"有哪些工具可用"的说明，模型若回复"想调某工具"，该请求原样交回上层，本能力绝不代为执行。

**Why this priority**: 这是 Provider 存在的意义本身——没有精确路由，多 Agent 并存就是空话；没有"只翻译不执行"，工具会被重复执行且绕过安全检查。这一条通了才有 MVP。

**Independent Test**: 配置两家 provider，各发一次调用，验证各自命中目标家、另一家零调用；附带工具说明发一次调用，验证模型的工具调用请求被原样透传、本能力零执行。

**Acceptance Scenarios**:

1. **Given** 实例配置了 deepseek 和 kimi 两家 provider，**When** 使用声明 kimi 的 Profile 发起一次调用，**Then** 请求发往 kimi，deepseek 全程零调用，响应原样返回。
2. **Given** Profile 声明的 provider 名在实例清单中不存在，**When** 发起调用，**Then** 立即得到明确报错（指出是哪个名字找不到），绝不静默改用其他家。
3. **Given** 调用附带了可用工具的说明，**When** 模型返回"想调用某工具"的请求，**Then** 该请求原样出现在返回结果中，本能力未执行任何工具。

---

### User Story 2 - 每次调用可审计，成败都留痕 (Priority: P2)

审计员事后要能查到：某次会话调了哪家 provider、哪个 model、输入输出各多少 token、耗时多久；一次失败的调用（超时、限流、模型报错）同样要留下记录，包含失败标识和人能读懂的失败原因，并按会话关联。

**Why this priority**: 可审计是产品的核心差异化卖点，且审计数据必须从第一天写入——事后从日志反解析等于返工。它依赖 P1 的调用链路存在，故为 P2。

**Independent Test**: 发起一次成功调用和一次注定失败的调用（如无效凭证），分别验证审计记录存在、字段完整、成败标识正确。

**Acceptance Scenarios**:

1. **Given** 一次成功的模型调用，**When** 调用完成，**Then** 留下恰好一条审计记录：provider、model、token 用量、耗时、成功标识、所属会话。
2. **Given** 一次失败的模型调用（如网络超时），**When** 异常发生，**Then** 审计记录先落（失败标识 + 失败原因），异常再继续抛给上层——记录与报错二者都不缺。

---

### User Story 3 - 配置即运维：换模型零代码，坏配置不拖垮系统 (Priority: P3)

管理员给某个 Agent 换模型，只改该 Agent Profile 里的 provider/model 字段；系统启动时加载全部 Agent 配置文件，个别文件损坏只记错误、不阻断其余 Agent 正常可用；所有凭证从环境变量注入，任何代码、配置文件、日志里都不出现明文。

**Why this priority**: 这是长期运维体验和安全底线，建立在 P1/P2 的机制之上。

**Independent Test**: 修改 Profile 的 model 字段后重启，验证调用命中新模型；放置一个坏配置文件，验证其余 Agent 不受影响；全文检索验证无明文凭证。

**Acceptance Scenarios**:

1. **Given** Agent 的 Profile 把 model 从 A 改为 B，**When** 重新加载后发起调用，**Then** 请求使用 model B，全程无代码改动。
2. **Given** 配置目录里有一个格式损坏的 Profile 文件，**When** 系统启动，**Then** 该文件的错误被记录，其余 Profile 全部正常加载可用。
3. **Given** 系统运行中，**When** 在代码与配置全文中检索凭证特征，**Then** 检索结果为零（凭证仅存在于环境变量）。

---

### Edge Cases

- Profile 引用的 provider 名在实例清单中不存在 → 显式报错，指明名字，不静默降级。
- Profile 文件 YAML 语法损坏 → 记错误日志、跳过该文件，不阻断启动，其余 Profile 正常。
- 凭证对应的环境变量未设置 → 启动/调用时给出清晰报错，而不是带着空凭证调用后报一个难解的错误。
- 模型调用中途失败（超时、限流、5xx）→ 审计记录先落（失败标识 + 原因），异常继续上抛，本能力不重试不换家。
- 模型响应中包含工具调用请求 → 原样透传，本能力零执行。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 每个 Agent 的模型选择必须由其 Profile（YAML 配置）声明——用哪家 provider、哪个 model、什么温度；系统启动时从约定目录加载全部 Profile，单个坏文件记错误但不阻断启动。
- **FR-002**: 实例级必须声明可用 provider 清单及各家凭证来源（环境变量占位）；Profile 引用清单中不存在的 provider 名时，必须显式报错，禁止静默跳过或默认替换。
- **FR-003**: 上层传入会话标识、Profile、提示内容，系统必须按 Profile 选中对应模型、发起一次调用、把结果原样返回；多家并存时路由必须精确、零串台。
- **FR-004**: 调用请求必须支持附带可用工具的说明（schema）；模型返回工具调用请求时必须原样交回上层——本能力只做格式翻译，绝不执行工具，必须关闭底层框架自带的自动工具执行机制。
- **FR-005**: 每次调用不论成败必须留下恰好一条审计记录：provider、model、token 用量、耗时、成功标识、失败原因（失败时），按会话关联；失败路径必须先落记录再抛出异常。
- **FR-006**: 凭证必须只从环境变量读取；代码、配置文件、日志中不得出现明文凭证。

### Key Entities

- **Profile**: 一个 Agent 的完整配置载体，本节消费其"模型选择"部分（provider 名、model、温度），同时承载后续各节将使用的全部字段（工具、技能、通知渠道、定时等），本节一并建全。
- **Provider 清单（实例级配置）**: 声明本实例接入了哪些 provider、各家凭证来自哪个环境变量；是 Profile 中 provider 名的合法性依据。
- **LLM 调用审计记录（llm_calls）**: 一次模型调用的完整留痕——所属会话、provider、model、token 用量、耗时、成功标识、失败原因。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 两家 provider 并存时，100% 的调用命中 Profile 声明的那家，另一家零调用（串台率为 0）。
- **SC-002**: 引用不存在 provider 名的调用，100% 得到指明名字的明确报错，静默降级发生率为 0。
- **SC-003**: 任意一次调用（成功或失败）之后，审计记录恰好新增一条且字段完整；失败调用的记录包含可读的失败原因。
- **SC-004**: 给一个 Agent 更换模型只需修改配置文件，代码改动为 0 行。
- **SC-005**: 附带工具说明的调用中，本能力执行工具的次数为 0（工具调用请求 100% 原样透传）。
- **SC-006**: 在代码与配置全文中可检索到的明文凭证数量为 0。
- **SC-007**: 存在一个坏 Profile 文件时，其余 Profile 的可用率为 100%（启动不被阻断）。

## Assumptions

- 目标 provider 的接入依赖在项目锁定的依赖版本清单中可用；课件中的 provider 名（deepseek/kimi/qwen）仅为示意，实际接入哪几家以依赖验证结果为准。
- 本能力的直接下游消费者是第 17 节的 ReAct 循环；本节交付后暂以测试作为唯一调用方。
- 核心阶段单实例、内网部署；并发与多实例路由一致性不在本节范围。
- 审计记录本节只做写入，查询接口与报表属于扩展阶段。
- 验收的自动化部分由课件《第16节》"验收 harness"测试套件承载；人工项（依赖验证、真实冒烟、明文凭证检索）见课件"五、做完怎么验"。
- （clarify 记录，合理默认）Profile 未声明 temperature 时不传该参数、使用 provider 侧默认值。
- （clarify 记录，合理默认）审计记录写入自身失败时：记 ERROR 日志、不阻断本次模型调用——可用性优先于审计完整性；核心阶段审计落本地文件库，失败概率极低。
