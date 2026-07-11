# Feature Specification: Tool 体系——Agent 真正能动手干事的那双手

**Feature Branch**: `class-20`（用户指定，勿另建）

**Created**: 2026-07-11

**Status**: Draft

**Input**: User description: "第20节需求：Tool 体系——Agent 真正能动手干事的那双手。……（完整需求见第20节课件《Tool 体系 原理解析、实现与代码讲解》一、二部分）"

## Clarifications

### Session 2026-07-11

- Q: 内置工具的注册形态？ → A: 内置 File/Shell/Http 走注解式管道（方法注解 → schema 自动生成 → 包装为统一抽象），与业务方方式三共用同一条管道验证其可用性；notify（19 节已是统一抽象形态）与 MCP 工具走直接注册。注册表因此有两条注册路径：直接注册与注解扫描注册——这正是"三种来源"测试的对应物（默认自答，停点供确认）。
- Q: "越界会被拦"在白名单实现（23/24 节）就位前怎么测？ → A: 安全校验接口本节前向定义（enforce 校验不过抛违规异常），内置工具第一步调用它；测试注入"拒绝一切"的校验替身断言工具确实先过校验且异常传播、真实白名单行为归 24 节测试（默认自答，停点供确认）。
- Q: shell 工具超时的默认值？ → A: 30 秒（课件与技术方案未给数值；配置化归 24 节 shell 白名单配置一并处理，本节先常量+javadoc 注明）。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 统一抽象与注册表：循环对来源无感知 (Priority: P1)

内置的、业务方 Java 写的、外部 MCP 接进来的工具，全部以统一抽象注册进一个注册表；ReAct 循环与执行器只跟抽象打交道。每个 Agent 按自己配置的 tools 字段拿到精确的可用子集。

**Why this priority**: 这是整个 Tool 体系的地基；没有它，后面每种来源都要在循环里开特例。

**Independent Test**: 向注册表分别注册三种来源的工具，断言都以统一抽象可查；按 tools 字段过滤断言子集恰好等于声明列表（不多不少）。

**Acceptance Scenarios**:

1. **Given** 三种来源各一个工具注册进注册表，**When** 以统一抽象遍历，**Then** 三个都在且四要素可用。
2. **Given** 注册表含 9 个工具、某 Agent 声明 tools=[read_file, http_get]，**When** 过滤，**Then** 结果恰好这两个——多一个是没过滤干净、少一个是过滤过头。
3. **Given** 任一注册工具，**When** 检查契约三件套（名称/描述/参数 schema），**Then** 全部非空——参数化遍历，新工具自动纳入检查。

---

### User Story 2 - 六个内置工具：能干活且越不了界 (Priority: P1)

read_file / write_file / list_dir / shell / http_get / http_post 六个内置工具交付：给合法输入能跑通拿到结果；每个工具执行的第一步过安全校验，校验不过直接拦下、动作根本不发生。

**Why this priority**: "真的去查天气"靠 http_get；工具能读文件跑命令发请求，一旦被乱调就是事故——功能对且踩不出边界是工具最基本的两条。

**Independent Test**: 每个工具两条用例：正常输入 execute 成功且内容非空；注入拒绝校验替身后 execute 被拦、外部动作零发生。

**Acceptance Scenarios**:

1. **Given** 临时目录里有文件，**When** read_file/list_dir/write_file 以合法路径执行，**Then** 内容读到/列到/写入成功。
2. **Given** shell 执行 echo 类无害命令，**When** execute，**Then** 拿到标准输出；命令长时间不结束时按超时（默认 30 秒）终止并报失败。
3. **Given** 本地假服务，**When** http_get/http_post 执行，**Then** 取回响应体/完成提交。
4. **Given** 校验器拒绝该动作，**When** 任一工具 execute，**Then** 违规异常抛出、文件未动/命令未跑/请求未发。

---

### User Story 3 - MCP 接入：外部工具进来，失联不拖垮 (Priority: P2)

按配置文件连接外部 MCP server：启动时取工具列表、逐个包装注册；调用时按协议转发、结果包成统一结果（失败可重试）。某个 server 失联只告警跳过——外部依赖的可用性不是自己的可用性。

**Why this priority**: 零代码/轻代码两档 Plugin Tool 的运输层；31 节日报 Agent 硬依赖它。

**Independent Test**: mock 客户端——好 server 的工具照常注册、坏 server 抛连接异常时启动不炸且它的工具不在注册表；执行转发参数原样、结果正确包装。

**Acceptance Scenarios**:

1. **Given** 两个配置的 server 一好一坏，**When** 启动连接，**Then** 不抛异常；好 server 工具在注册表、坏的不在、告警日志留痕。
2. **Given** MCP 工具被调用，**When** execute，**Then** 参数原样转发给对应 server，成功结果包装返回；调用失败包成"失败且可重试"。
3. **Given** 配置文件（name/transport/command/env），**When** 加载，**Then** 逐项解析；文件缺失按零 server 处理不报错。

---

### User Story 4 - 注解式接入：方式三管道 (Priority: P2)

业务方（和内置工具自己）把 Java 方法标上注解：参数 schema 自动生成、自动包装成统一抽象注册。写法与内置工具完全一样，进程内直调，性能最好。

**Why this priority**: 重代码档是集成深度最好的路；内置工具走同一条管道，管道本身就被日常使用持续验证。

**Independent Test**: 一个注解方法经管道注册后，从注册表取出断言 name/description/schema 齐全且 schema 含参数描述；execute 传 JSON 实参断言方法真的被调、返回值包成统一结果。

**Acceptance Scenarios**:

1. **Given** 标注注解的方法（带参数说明），**When** 注册，**Then** 注册表可查、schema 自动生成含该参数。
2. **Given** 该工具被 execute，**When** 传 JSON 实参，**Then** 方法以正确实参执行、返回内容进统一结果；方法抛异常时包成失败结果语义（由执行层统一处理）。

---

### Edge Cases

- 注册重名工具 → 明确报错拒绝（静默覆盖会让两个来源打架且难查）。
- Profile tools 声明了注册表里不存在的名字 → 过滤结果不含它（PromptBuilder 既有行为），不报错——工具可能来自尚未连接的 MCP server。
- read_file 读不存在的文件 / list_dir 列不存在的目录 → 失败结果点名路径，不抛裸异常。
- shell 命令非零退出码 → 失败结果携带 stderr；超时 → 失败结果注明超时。
- http 非 2xx → 失败结果带状态码（工具层不同于 notify：查询类失败模型可自救，包结果不抛）。
- MCP server 返回空工具列表 → 正常，零注册不报错。
- mcp_servers.yaml 不存在 → 视为零 server，启动照常。
- 校验器未装配（如单元环境）→ 工具构造强制要求校验器参数，不允许"没有校验器就裸奔"。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 以统一工具抽象（名称/描述/参数 schema/执行）承载所有来源的工具，注册进唯一注册表；循环与执行器 MUST 只依赖抽象；重名注册 MUST 报错。
- **FR-002**: MUST 交付六个内置工具：read_file、write_file、list_dir、shell（带超时，默认 30 秒）、http_get、http_post；与既有 notify（本节注册）、save_memory/recall_memory（22 节注册）构成九个内置工具面。
- **FR-003**: 每个涉外内置工具 execute 的第一步 MUST 调用安全校验接口（文件→路径、Shell→命令、HTTP→域名）；校验抛违规异常时动作 MUST 零发生；校验接口本节前向定义、白名单实现归 23/24 节。
- **FR-004**: MUST 提供注解式接入管道：方法注解 → schema 自动生成 → 包装为统一抽象注册；内置工具与方式三共用。
- **FR-005**: MUST 提供 MCP 接入：读配置（name/transport/command/env）连接、tools/list 包装注册、调用按协议转发、结果含可重试标记；单个 server 失联 MUST 告警跳过不拖垮启动，其余照常。
- **FR-006**: 注册表 MUST 支持按 tools 字段过滤，结果与声明的存在项精确相等；运行时装配 MUST 用注册表替换既有空工具表，接通 17 节执行器与 Prompt 组装、18 节 chat 链路。

### Key Entities

- **统一工具抽象（OryxTool，16/17 节已交付）**: 四要素契约；本特性不改它。
- **注册表（ToolRegistry）**: 名称→工具的唯一权威；直接注册 + 注解扫描注册两条路径；按 tools 过滤。
- **安全校验（Sandbox 接口，前向）**: enforce(动作) 不过即抛违规异常；动作 = 类型（文件读/写、命令、HTTP）+ 目标。
- **MCP 接入（McpClientService + McpToolAdapter）**: 连接维护/注册 与 协议转发/结果包装。
- **MCP 配置（mcp_servers.yaml）**: name/transport/command/env 列表。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 注册表内每个工具 100% 通过契约三件套检查（参数化遍历，新工具自动纳入）。
- **SC-002**: 六个内置工具 12 条基本用例（正常+拦截）全绿；被拦时外部动作零发生。
- **SC-003**: 单个 MCP server 失联时启动成功率 100%，其余工具注册率 100%。
- **SC-004**: 按 tools 过滤的子集与声明的存在项 100% 精确相等。
- **SC-005**: chat 链路里 Agent 可真实调用 http_get 完成查询（Demo 一动作落地——真模型人工项）。

## Assumptions

- OryxTool（含 getInputSchema）/ToolResult 16/17 节已交付于 core；17 节 ToolExecutor（异常转失败+审计）与 PromptBuilder（按 tools 过滤）是消费方；18 节 OryxOsRuntime 是装配点（本节把空工具表替换为注册表内容——属预告接线，17/19 节 plan 均注明"20 节接线"）。
- 注解式管道与 MCP 客户端使用项目锁定 BOM 内组件（spring-ai-core 的 @Tool 生态、spring-ai-mcp——H0 已实核在位）；新增依赖在 plan 列明。
- 安全校验接口为 23/24 节交付物的前向最小定义（课件明文"先以接口调用形式接入"）；MemoryTools 归 22 节。
- 明确不做：Tool Policy、按需加载、自身作为 MCP server、容器级沙箱、多工具并行调用。
