# Feature Specification: CLI——OryxOS 的命令行入口

**Feature Branch**: `class-18`（用户指定，勿另建）

**Created**: 2026-07-10

**Status**: Draft

**Input**: User description: "第18节需求：CLI——OryxOS 的命令行入口。……（完整需求见第18节课件《CLI：功能概述、实现思路与代码讲解》一、二部分）"

## Clarifications

### Session 2026-07-10

- Q: 会话标识的具体拼接格式？ → A: `channel:user:profile`（冒号分隔三元组，如 `cli:wang:default`）。课件与技术方案只定"联合生成"未定格式，取可读可测试的最简形态；格式本身不对外承诺，唯一承诺是"只在会话管理者内部拼接"（默认自答，停点供确认）。
- Q: 会话状态 archived 何时产生？ → A: 本节只产生与更新 `active`；归档动作属第 26 节 Web Service 的 DELETE 端点，本节仅建列不写入 archived（含 archived_at 恒空）。
- Q: chat 的"当前用户"取什么？ → A: 运行环境的系统用户名（无认证体系时的最稳定标识）；核心阶段不做认证，26 节 Web 入口的 user 由请求方提供。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 终端里跟 Agent 说上话 (Priority: P1)

开发者在终端敲 `oryxos chat`，进入交互式对话：问"今天天气怎么样，穿什么合适"，Agent 调工具、查天气、给建议；继续追问、继续答；输入 `/quit` 正常退出。隔天再进来，上次聊过什么还在——多轮对话靠同一条会话串起来。

**Why this priority**: 这是 Provider、ReAct 之后第一个"看得见摸得着"的入口——到这一步用户才能真正跟自己搭的 Agent 说上话，Demo 一的完整体验靠它撑起。

**Independent Test**: 用测试替身代替引擎，驱动一段"多行输入 + /quit"的脚本化交互，验证每行输入都转交引擎、回复都打印、/quit 退出；会话幂等由会话层测试独立钉死。

**Acceptance Scenarios**:

1. **Given** 默认 Agent 就绪，**When** 用户敲 `oryxos chat` 并连续输入两句话，**Then** 每句话都交给引擎处理并打印回复，两句话共享同一条会话。
2. **Given** 交互进行中，**When** 用户输入 `/quit`，**Then** 命令正常退出，不再读取输入。
3. **Given** 用户上次用 chat 聊过，**When** 同一用户再次进入 chat（同渠道同 Agent），**Then** 续用同一条会话，历史完整。
4. **Given** 用户敲 `oryxos chat --profile weather`，**When** 对话发起，**Then** 使用 weather 这个 Agent 处理（默认不传则用 default）。

---

### User Story 2 - 会话口径一次钉死：幂等与隔离 (Priority: P1)

会话标识由三元组（渠道+用户+Agent）唯一决定，生成只发生在会话管理者内部一处。同一三元组反复获取永远是同一条会话；三元组任何一个元素不同就是不同会话。CLI、Web、定时——所有入口都只报三元组，谁都不许自己拼字符串。

**Why this priority**: 会话层是后面所有入口共用的地基。两处各拼一遍、格式差一个分隔符，同一个人就会出现两条互不相认的历史——这类口径问题最难查，必须在第一个用起来会话的入口这一节钉死。

**Independent Test**: 对会话管理者连续两次以同一三元组获取，断言拿到同一会话；改变任一元素，断言是不同会话。

**Acceptance Scenarios**:

1. **Given** 会话管理者就绪，**When** 以 ("cli","wang","default") 两次获取，**Then** 两次返回的会话标识相同（幂等）。
2. **Given** 同上，**When** 以 ("web","wang","default") 获取，**Then** 与 cli 那条是不同会话（渠道隔离）；换 user 或 profile 同理。
3. **Given** 代码库全量检索，**When** 查会话标识的拼接逻辑，**Then** 只存在于会话管理者内部一处。

---

### User Story 3 - 会话落库与跨重启恢复 (Priority: P1)

会话持久化到 sessions 表：标识、所属 Agent、渠道、用户、整体序列化的对话历史、状态、三个时间戳。进程重启后，按同一三元组进来还能拿回完整历史。

**Why this priority**: "隔天再聊还记得"是会话存在的意义；同时它是第四周"Session 跨重启恢复"验收的地基。

**Independent Test**: 存一条含多轮消息的会话，模拟重启（新建数据访问上下文重查），断言消息一条不丢、顺序不变。

**Acceptance Scenarios**:

1. **Given** 手工建表脚本建出的 sessions 表，**When** 保存一条含用户/助手/工具三类消息的会话并按标识查回，**Then** 历史序列化回读后消息完整、顺序不变。
2. **Given** 已保存的会话，**When** 模拟进程重启后按同一三元组获取，**Then** 拿回同一条会话及其全部历史。
3. **Given** 会话有活动，**When** 保存，**Then** last_active_at 更新；新建时 created_at/status 正确。

---

### User Story 4 - 12 个子命令与轻重分流 (Priority: P2)

一个命令行入口挂 12 个子命令：跑 Agent 的（chat/serve/gateway）、看情况的（status、profile list/create/show/delete、provider list、tool list、session list）、起项目的（init）。"看一眼就退"的轻命令直接读写文件秒回，不启动重运行时；只有要跑引擎的命令才付启动代价。

**Why this priority**: 命令面数量多但逻辑浅；分流标准（要不要调模型/跑引擎）一开始定死，否则要么全都慢、要么后面改起来伤筋动骨。

**Independent Test**: 逐个执行 12 个命令的帮助与基本路径；轻命令在无重运行时依赖的情况下可独立执行完成。

**Acceptance Scenarios**:

1. **Given** 已构建的可执行入口，**When** 依次运行 12 个子命令的 `--help`，**Then** 每个都有帮助信息、未知参数有统一报错。
2. **Given** `.oryxos/profiles/` 下有若干 YAML，**When** 运行 `oryxos profile list`，**Then** 列出全部 Profile 名，全程不启动重运行时、秒级返回。
3. **Given** 空目录，**When** 运行 `oryxos init`，**Then** 创建出 `.oryxos/` 工作区骨架（profiles/skills/memory 等目录与 Bootstrap 占位文件）。

---

### User Story 5 - 重命令启动即校验装配完整 (Priority: P2)

chat/serve/gateway 启动完整运行时后，数据访问层必须装配完整——存储在独立模块，扫描范围必须显式声明。"仓库接口数量为 0"这类装配残缺不允许带病运行：审计和会话会静默写不进去，等发现时已经丢了数据。

**Why this priority**: 这是真实踩过的坑（课件坑四）：照"轻重分流、重命令才启动运行时"的思路走几乎绕不开，必须提前想到并用测试钉住。

**Independent Test**: 以重命令同款配置启动一次测试上下文，断言会话与两张审计表的仓库 Bean 都在、能完成一次写读。

**Acceptance Scenarios**:

1. **Given** 重命令使用的启动配置，**When** 启动上下文，**Then** 会话/审计仓库全部就位（数量 > 0），写读通过。
2. **Given** 三种运行模式，**When** 分别启动，**Then** 共享同一份 Profile 配置与同一套会话存储（数据不因模式切换而丢）。

---

### Edge Cases

- chat 输入空行 → 不转交引擎，继续读下一行（空消息没有处理意义）。
- chat 引用的 Profile 不存在 → 启动即报错点名，不进入交互循环。
- `/quit` 前后有空白（" /quit "）→ trim 后判断，照常退出。
- 输入流关闭（EOF/Ctrl-D）→ 等同退出，不抛异常堆栈。
- 同一三元组并发 getOrCreate（虚拟线程）→ 不产生两条会话（同库唯一主键兜底）。
- messages_json 为空的新会话 → 正常保存与恢复（零消息不是错误）。
- init 在已初始化目录再次运行 → 幂等，不覆盖已有文件、不报错。
- session list 在库文件尚不存在时 → 友好提示"暂无会话"，不抛异常。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供唯一命令行入口，注册 12 个子命令：init、status、chat、serve、gateway、profile list/create/show/delete、provider list、tool list、session list；每个命令 MUST 有帮助信息，参数解析与报错提示统一。
- **FR-002**: 命令行层 MUST 只做"读输入→转交引擎→打印结果"，MUST NOT 包含任何 Agent 逻辑（不想、不调模型、不执行工具）。
- **FR-003**: 命令 MUST 按轻重分流：不调模型/不跑引擎的命令（init、status、profile 四件、provider list、tool list、session list）不启动重运行时、秒级返回；chat/serve/gateway 才启动完整运行时。
- **FR-004**: chat MUST 维护当前会话、逐行读入交给第 17 节统一处理入口、`/quit`（trim 后）退出、EOF 等同退出、空行跳过；`--profile` 指定 Agent，默认 `default`。
- **FR-005**: 会话标识 MUST 由三元组 channel+user+profile 联合唯一生成（格式 `channel:user:profile`，见 Clarifications），拼接 MUST 只发生在会话管理者内部一处；所有入口只提供三元组。同一三元组多次获取 MUST 幂等返回同一会话；任一元素不同 MUST 是不同会话。
- **FR-006**: 会话 MUST 持久化到 sessions 表（列：session_id 主键、profile_name、channel、user_id、messages_json、status、created_at、last_active_at、archived_at），历史整体 JSON 序列化一列；按标识 MUST 能跨进程重启恢复完整历史；表结构走手工建表脚本。
- **FR-007**: 三种运行模式 MUST 共享同一份 Profile 配置与同一套会话存储。
- **FR-008**: 重运行时启动 MUST 装配完整数据访问层（存储模块的实体与仓库显式声明扫描范围）；装配残缺 MUST 在启动/测试期暴露，不允许带病运行。

### Key Entities

- **会话记录（sessions 表一行）**: 一条会话的持久化形态——标识（三元组唯一决定）、所属 Agent、渠道、用户、整体序列化的历史、状态（active/archived）、三个时间戳。
- **会话管理者（SessionManager，第 17 节前向接口本节补全）**: 三元组→会话的唯一权威：获取或新建（幂等）、按标识查、保存；标识拼接只在其内部。
- **命令（12 个）**: 用户与系统交互的动作单元，分轻（文件操作直达）重（启动完整运行时）两类。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 用户在终端完成一次多轮对话（≥2 轮）并正常退出，全程无需接触任何 API 或代码；再次进入历史仍在。
- **SC-002**: 同一三元组 100 次获取产生且仅产生 1 条会话；三元组 8 种组合变化产生 8 条互不相同的会话——自动化回归钉死。
- **SC-003**: 会话含 N 条消息时跨重启恢复后仍是 N 条且顺序不变（零丢失）。
- **SC-004**: 轻命令（如 profile list）冷执行秒级返回（无重运行时启动等待）；重命令启动后数据访问层仓库数 > 0。
- **SC-005**: 12 个子命令 100% 可执行且有帮助信息；未知子命令/参数有清晰报错而非堆栈。

## Assumptions

- 第 16 节 Provider 与第 17 节 ReAct/AgentService/Session 领域对象为直接上游；第 17 节前向定义的会话管理最小接口（仅 save）由本节按课件补全为 getOrCreate/get/save 三方法（课件明文本节交付）。
- 命令行解析库已锁定于根 pom（Picocli 4.7.6），子命令/参数/帮助由它承担，不自己解析 args。
- sessions 表口径与前两节审计表一致：手工建表脚本、SQLite、不依赖自动迁移。
- chat 的"当前用户"取自运行环境（如系统用户名），核心阶段不做认证。
- 明确不做（边界）：IM Channel（企业微信/飞书/钉钉/Slack）属扩展阶段；serve 的 REST 端点实现属第 26 节（本节只留启动骨架）；定时触发属第 25 节；对话历史按条拆表不做。
