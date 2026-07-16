# Feature Specification: Web Service 与第一版管理平台——OryxOS 的对外门面

**Feature Branch**: `class-26`

**Created**: 2026-07-15

**Status**: Draft

**Input**: User description: "第26节：Web Service 把内部能力包装成 REST API（oryxos serve 启动），让业务系统能把 Agent 接进流程；顺带做第一版只读管理平台（Vue，跟官网首页同栈同视觉）。"

## Clarifications

### Session 2026-07-15

- Q: 错误/成功统一 JSON 信封用哪个？ → A: 复用 oryxos-web 已有的 `ApiResponse`（code/message/data/timestamp）——课件与技术方案里原写的 `ErrorBody` 反向同步为 `ApiResponse`，全站一套信封（成功与错误统一）。
- Q: 管理平台技术栈？ → A: Vue 3 + Vite SPA，与官网首页（`website/`）同栈、同视觉（深色 + 橙、见项目设计 token），由 `oryxos-admin-ui` skill 生成；构建串联走 frontend-maven-plugin（`npm build` 绑进 `mvn package`）。
- Q: 服务启动的 provider key 依赖？ → A: 只需一个 provider 环境变量即可启动——禁用框架对 LLM 模型的 eager 自动装配，Provider 由既有显式映射构造。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 业务系统连续对话 (Priority: P1)

业务系统通过 REST 把 Agent 接进自己的流程：先创建一个会话，再对该会话多次发消息（每次触发一次完整的"想→查→答"循环），随时查这个会话的历史，用完归档。这条路让"有状态多轮对话"能被任何能发 HTTP 的系统集成。

**Why this priority**: 这是 Web Service 存在的首要理由——把 CLI 独占的能力开放成 HTTP 通道。没有它，OryxOS 只是个本地 CLI 工具、业务系统无法集成。会话四端点单独可用即交付核心价值。

**Independent Test**: 建会话→发一条消息→查历史→归档，逐步断言：创建返回会话标识；发消息触发统一编排入口并返回回复；查历史拿到往来记录；归档后状态变更。切片层面 mock 编排入口断言"恰被调一次"。

**Acceptance Scenarios**:

1. **Given** 服务已启动，**When** 创建会话，**Then** 返回一个会话标识、后续可用它发消息。
2. **Given** 一个已存在的会话，**When** 发一条消息，**Then** 走统一编排入口跑完一轮、返回回复，历史追加。
3. **Given** 发消息时会话不存在，**When** 请求，**Then** 返回 404、统一信封、不触发编排。
4. **Given** 单条消息超过 32KB，**When** 发消息，**Then** 返回 400、不触发编排。
5. **Given** 一个有历史的会话，**When** 查历史，**Then** 返回最近至多 100 条；**When** 归档，**Then** 会话被标记归档。

### User Story 2 - 一次性无状态调用 (Priority: P1)

业务系统对某个 Agent 直接发一条消息、等它跑完拿结果，不关心会话——适合无状态短任务。

**Why this priority**: 与 US1 并列的另一条集成主路径；很多业务只要"问一句拿一个答复"，不需要维护会话。也是后续节（人推补跑定时 Agent）依赖的调试入口。

**Independent Test**: 对某 Agent 发一条消息，断言返回该 Agent 跑完的回复；底层走的是与 CLI/会话发消息同一个编排入口。

**Acceptance Scenarios**:

1. **Given** 一个已定义的 Agent，**When** 无状态调用并带一条消息，**Then** 返回它跑完一轮的回复。
2. **Given** 调用一个不存在的 Agent，**When** 请求，**Then** 返回 404、统一信封。

### User Story 3 - 运营方用管理平台只读观察 (Priority: P2)

运营方在浏览器打开管理平台（web manager），看当前有哪些会话、哪些 Profile、哪些 Tool、长期记忆内容、运行状态与各 Provider 连通情况。这一版只读——看得见、改不了。

**Why this priority**: 管理平台是 API 完备性的活证据（所有数据都来自只读端点），也给运营方一个不碰命令行的观察窗口。次于两条集成主路径，但显著提升可用性与"这是个 OS"的体感。

**Independent Test**: 启动服务、浏览器开管理平台，五个页面分别渲染五个只读端点的真实数据；构造一次端点错误，页面显示统一信封里的 message；确认界面上无任何新建/编辑/删除入口。

**Acceptance Scenarios**:

1. **Given** 服务已启动且与 REST 同端口同进程托管管理平台，**When** 打开管理平台首页，**Then** 五个页面（会话/Profile/Tool/记忆/状态）都能渲染真实数据。
2. **Given** 某只读端点返回错误，**When** 对应页面加载，**Then** 显示统一信封里的 message，不白屏、不弹裸堆栈。
3. **Given** 这一版只读，**When** 浏览任意页面，**Then** 界面上不存在任何创建/修改/删除 Agent 的入口。
4. **Given** 管理平台的视觉，**When** 与官网首页并排看，**Then** 是同一套设计语言（深色 + 橙、同字体）。

### Edge Cases

- **500 不泄漏**：内部异常（如数据库连接串、堆栈）MUST NOT 出现在对外 500 响应体里；对外只给统一话术，细节进日志。
- **Provider 故障 / 超时**：下游 Provider 不可用返回 503；Agent 调用超过 60 秒上限返回 504——都走统一信封，服务本身不崩。
- **CLI 与 REST 同源**：CLI 里聊过的会话，REST 查历史能查到同一份数据（共享存储、同一编排入口）。
- **启动只需一个 provider key**：缺少框架层的其它 LLM key（非本项目使用的 eager 自动装配所需）MUST NOT 导致启动失败。
- **管理平台子路由刷新**：管理平台是单页应用，其内部路径直接访问/刷新 MUST 正常打开（未命中静态资源回落到应用入口页），且 MUST NOT 影响 REST 端点路由。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 对外暴露一组统一前缀的 REST 端点，按资源分组，覆盖四类事：会话管理（创建 / 发消息 / 查历史 / 归档）、无状态 Agent 调用、信息查询（Profile 列表 / 长期记忆 / Tool 列表）、系统状态（健康 / 运行信息含各 Provider 连通状态）。
- **FR-002**: 发消息与无状态调用 MUST 走跟 CLI 完全一样的统一编排入口（同一个引擎）；接入层只做参数校验、响应包装、错误处理三件事，MUST NOT 夹带业务逻辑；CLI 与 REST 两个人推入口 MUST 看到同一份会话数据。
- **FR-003**: 所有响应 MUST 用同一个统一 JSON 信封（状态码、消息、数据、时间戳四要素）；所有异常 MUST 收敛到单一全局异常出口翻译成该信封，MUST NOT 各端点自拼错误格式。错误用标准 HTTP 语义：参数错误 400、资源不存在 404、内部错误 500、Provider 故障 503、Agent 调用超时（上限 60 秒）504。
- **FR-004**: 500 响应 MUST NOT 泄漏内部异常细节（连接串、堆栈等）；对外只给统一话术，细节仅进日志。
- **FR-005**: 系统 MUST 有请求防呆限制：单条消息最大 32KB（超限 400）、会话历史返回最多最近 100 条。
- **FR-006**: 系统 MUST 提供第一版管理平台（web manager）：跟官网首页同栈（Vue）、同视觉（深色 + 橙、依项目设计 token）的单页界面，五个页面分别调五个只读查询端点渲染真实数据，出错显示统一信封里的 message。
- **FR-007**: 管理平台这一版 MUST 只读，MUST NOT 出现任何新建 / 编辑 / 删除 Agent 的入口。
- **FR-008**: 管理平台 MUST 由 Web 服务同一进程、同一端口托管（一个 `serve` 命令同时给出 REST 与管理台）；管理平台作为单页应用，其未命中的前端路径 MUST 回落到应用入口页，MUST NOT 干扰 REST 端点路由。
- **FR-009**: 服务 MUST 能只靠一个 provider 环境变量启动——禁用框架对 LLM 模型的 eager 自动装配，Provider 由既有显式映射构造，MUST NOT 因缺其它框架 key 而启动失败。

### Key Entities *(include if feature involves data)*

- **统一响应信封**：所有端点的对外 JSON 外壳，含状态码、消息、数据、时间戳；成功与错误共用同一形状（复用既有 `ApiResponse`）。
- **REST 端点集**：按资源分组的对外接口（会话 / Agent 调用 / Profile / Memory / Tool / 系统状态），实际逻辑委托既有核心服务。
- **管理平台**：只读单页界面，数据全部来自只读查询端点，与 REST 同进程托管。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 会话四端点 + 无状态调用 + 三个查询端点 + 两个系统端点（共 10 个）全部真实链路可达——冒烟集成对健康/运行信息/Profile/Tool 可达性 100% 通过（验证 Bean 装配与跨模块扫描没炸）。
- **SC-002**: 发消息 / 无状态调用 100% 走统一编排入口（切片测试断言恰被调一次、接入层不夹带私货）。
- **SC-003**: 每类异常 100% 映射到约定状态码（400/404/500/503/504）、响应体都是统一信封——关键回归恒绿。
- **SC-004**: 500 响应体 0% 含内部异常 message（连接串/堆栈不外泄）——关键回归恒绿。
- **SC-005**: 消息超 32KB 100% 被拒（400 且不触发编排）；历史返回条数 ≤ 100。
- **SC-006**: 管理平台五页 100% 渲染真实数据、无任何写操作入口、视觉与首页一致；错误页显示统一信封 message、不白屏。
- **SC-007**: 服务只配一个 provider 环境变量即可启动成功（不因缺其它框架 key 失败）。
- **SC-008**: 前序节全部测试保持全绿（本节只做对外包装，不改任何前序契约）。

## Assumptions

- 统一编排入口、会话管理、Profile / Tool / Memory / Provider 查询所需的核心服务均前序节交付，本节只做对外包装、不改其契约。
- 统一响应信封复用 `oryxos-web` 已有的 `ApiResponse`（第24节窗口随白名单管理端点预交付，已被现有全局异常出口与测试采用）；课件与技术方案里原写的 `ErrorBody` 反向同步为 `ApiResponse`，全站一套信封。
- 全局异常出口在既有实现上扩展（补会话不存在 / Provider 故障 / 超时 / 消息超限映射与 500 不泄漏），属既有实现的扩展点，不是另起一套。
- 管理平台前端用项目内 `oryxos-admin-ui` skill 生成（内含首页设计 token 与规范）；构建产物由 Web 服务托管在 `/admin` 子路径，SPA 未命中路径回落入口页；前端构建串联进主构建（frontend-maven-plugin）。
- 边界（本节不做）：认证鉴权（假设内网，扩展阶段补）、SSE 流式、WebSocket、限流、RBAC、Profile / Memory 的写入端点、通过 API 定义 / 管理 Agent 的增删改（第29/30节正题——本节管理平台只读、Agent 调用只做无状态一个端点）。
- 并发依赖既有虚拟线程模型（同步阻塞代码 + 每请求虚拟线程），本节不引入异步编程模型。
