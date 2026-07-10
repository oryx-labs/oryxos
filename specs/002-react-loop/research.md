# Research: ReAct 循环——Agent 的大脑

**Date**: 2026-07-10 | **Feature**: [spec.md](./spec.md)

各决策的"验证方式"注明了本地实核手段（H3：写码前先核实，不臆造 API）。

## D1. 依赖环破解：Provider 调用契约上移 oryxos-core ⚠️ 软门禁项

- **Decision**: 把 Provider 的调用契约从 `io.oryxos.provider` 移入 `io.oryxos.core.provider`：
  - `ProviderService` 抽为 **core 接口**（方法签名 `ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request)` 逐字保真）；
  - 值对象 `ProviderRequest` / `ProviderResponse` / `ToolCallRequest` / `Usage` 原样移动（record，无行为变化）；
  - `LlmCallAuditor` 接口原样移动；
  - oryxos-provider 里的原具体类改名 `SpringAiProviderService implements ProviderService`，实现体一行不动；
  - oryxos-storage 的 `JpaLlmCallAuditor` 改 import core 接口，**storage pom 移除 oryxos-provider 依赖**。
- **Rationale**: 落位表定死 `ReActLoop`→oryxos-core、`ProviderService` 实现→oryxos-provider，而 Maven 中 provider→core 依赖已存在（provider 用 `Profile`/`OryxTool`），core 再依赖 provider 即循环依赖，无法编译。契约上移是唯一既保落位表又保签名字面量的解法；"模块之间通过接口解耦"本就是 CLAUDE.md 明文设计原则。副产品：16 节验收报告遗留 flag（storage→provider 反向依赖，当时已注明 alternative = move interface to core）就此解决。
- **Alternatives considered**:
  - B. core 另立文档外窄接口（如 LlmGateway），provider 适配——违 H5"不建文档外抽象层"，且值对象要在 core 重复定义一套，两套类型互转纯属噪音。
  - C. `ReActLoop` 挪到 oryxos-provider 或 oryxos-boot——违 TechSol §10 落位表（core 含 ReActLoop），且 boot 是装配层不该有领域逻辑。
  - D. 合并 core 与 provider 模块——违"模块结构（9 个）"总设计。
- **影响面（16 节回归）**: `ProviderServiceTest`（类名/import 改为 SpringAiProviderService）、`ToolSchemaAdapterTest`（不受影响，ToolSchemaAdapter 不动）、`ProviderSmokeIT`、`JpaLlmCallAuditor`、`LlmCallRepositoryTest`——仅 import/类名机械替换，断言零改动；16 节全部测试必须回归绿（H4）。
- **验证方式**: `mvn clean verify` 全绿 + `grep -r "io.oryxos.provider" oryxos-core/src/main` 零命中（core 不依赖 provider 包名）。

## D2. 前向最小 Session/Message/SessionManager ⚠️ 软门禁项（清单外类型）

- **Decision**: oryxos-core 新建 `io.oryxos.core.session` 包：
  - `Session`：可变领域对象，字段 `sessionId`、`profileName`、`List<Message> messages`（内部可变、对外只读视图）；方法 `appendUser(String)`、`appendAssistant(ProviderResponse)`、`appendToolResult(String toolName, ToolResult)`。
  - `Message`：record `(String role, String content, String toolName)`，role 取值 `user` / `assistant` / `tool`（toolName 仅 tool 角色非空）。
  - `SessionManager`：接口，本节仅 `void save(Session session)`。
- **Rationale**: 课件已定字面量 `run(Session, userMessage, profile)`、`process(Session, userMessage)`、`sessionManager.save(session)` 离开这三个类型无法成立；TechSol §10 明文 oryxos-core 含 `Session`。18 节交付 JPA 实体 + Repository + `getOrCreate(channel, userId, profileName)` 完整签名与 session_id 拼接规则——本节不预支（session_id 只透传不生成，H4④）。
- **Alternatives considered**: 用 `List<String>` 或 Map 顶替（丢角色信息，PromptBuilder 无法区分谁说的）；把 Session 放 storage（违 TechSol §10；领域对象不是 JPA 实体）。
- **验证方式**: 编译 + ReActLoopTest 断言累积顺序。

## D3. ToolExecutor 形态：Map 注入 + 审计接口 + 沙箱留位

- **Decision**: `ToolExecutor` 构造注入 `Map<String, OryxTool> tools` 与 `ToolInvocationAuditor`；`execute(String sessionId, ToolCallRequest call)` 流程：查表（未找到→失败结果，同样落审计）→（沙箱检查位，注释注明 24 节 `SandboxChecker` 接线，不造空壳）→ `tool.execute(input)` 计时 → 成败都 `auditor.record(...)` → 返回 `ToolResult`。工具抛出的 `RuntimeException` 转失败 `ToolResult`（`errorMessage=e.getMessage()`）落审计后交还循环，不上抛不中断（spec Edge Case / FR-005）。
  - `ToolInvocationAuditor` 接口（core）：`void record(String sessionId, String toolName, String inputJson, String resultJson, boolean success, String errorMessage, long durationMs)`——列与 `tool_invocations` 表一一对应，口径抄 16 节 `LlmCallAuditor`。
- **Rationale**: 20 节才交付 `ToolRegistry`，本节用最小 Map 注入（零新公共概念，20 节接线时由 Registry 提供该 Map）；审计接口放 core 才能被 `ToolExecutor` 引用（storage 实现，方向与 D1 一致）。
- **Alternatives considered**: 提前定义 ToolRegistry 接口（清单外且 20 节签名未定，必错）；ToolExecutor 直接依赖 JPA Repository（core 不能依赖 storage，方向反了）。
- **验证方式**: ToolExecutorTest 成败双路径断言审计参数。

## D4. PromptBuilder：四段拼接产出 ProviderRequest

- **Decision**: `build(Session session, Profile profile)` 返回 `ProviderRequest`：
  - `content` = 四段按序拼接的单段文本：①`ContextLoader.load(profile)`（identity.prompt + Bootstrap + Skill）+ 末尾一行 `当前日期时间：yyyy-MM-dd HH:mm（zzz）`；②长期记忆——22 节未交付，本节恒空跳过（框架顺序保留，代码留位注释）；③会话历史——按 Clarification "一轮=一条 user 消息及其后全部消息" 截最近 `maxHistoryTurns` 轮，逐条以 `role: content` 行渲染；④工具部分不进 content——经 `ProviderRequest.availableTools` 传递（16 节 `ToolSchemaAdapter` 负责转 Function Calling 格式，这就是课件"可用工具列表"的落点）。
  - 时钟经构造注入 `java.time.Clock`（默认 `Clock.systemDefaultZone()`），测试用 `Clock.fixed` 断言日期时间行。
- **Rationale**: 课件明文"把四部分按顺序拼成**一段** Prompt"；16 节 `ProviderRequest.content` 即单段文本，接口零改动。工具列表走 availableTools 而非文本，是因为 Function Calling 的 schema 挂载 16 节已定由 ToolSchemaAdapter 做，PromptBuilder 再拼文本描述就是两条路（违"工具信息单一来源"）。
- **Alternatives considered**: 多消息（SystemMessage/UserMessage）结构——16 节 ProviderRequest 无此形态，改它违软门禁 2 且课件语义就是单段。
- **验证方式**: PromptBuilderTest 断言四段顺序、截断、日期行；`Clock` 为 JDK 标准 API 无需核实第三方。

## D5. ReActLoop 骨架：课件代码逐行对应

- **Decision**: `run(Session session, String userMessage, Profile profile)`：`session.appendUser(userMessage)` → for i < `profile.settings().maxIterations()`：`promptBuilder.build` → `providerService.chat(session.sessionId(), profile, request)` → `session.appendAssistant(resp)` → 无 toolCalls 则 `return resp.text()`（null 视为空串收尾，spec Edge Case）→ 有则逐个 `toolExecutor.execute(session.sessionId(), call)` + `session.appendToolResult(...)` → 下一轮；循环耗尽 `return "达到最大轮数，已停止"`（课件字面量）。
- **Rationale**: 与课件三节骨架逐行同构；强制收尾文案含"达到最大轮数"是 harness 断言点。
- **Alternatives considered**: while(true)+计数（等价但离课件形态远）；工具并行执行（明确不做）。
- **验证方式**: ReActLoopTest（含关键回归 times(10)）。

## D6. AgentService + ProfileContext：ThreadLocal 生命周期

- **Decision**: `ProfileContext`：`private static final ThreadLocal<Profile>`，静态方法 `set(Profile)` / `current()`（返回 Profile 或 null）/ `clear()`（用 `ThreadLocal.remove()` 防泄漏，虚拟线程每请求独立）。`AgentService.process(Session session, String userMessage)`：`profileRegistry.get(session.profileName()).orElseThrow(点名报错)` → `ProfileContext.set(profile)` → try { `reActLoop.run` → `sessionManager.save(session)` → return reply } finally { `ProfileContext.clear()` }。异常路径不 save（Clarification 2）。
- **Rationale**: 课件骨架逐行同构；`remove()` 而非 `set(null)` 是 ThreadLocal 泄漏的标准防法（P3C 也查 ThreadLocal 未 remove）。
- **Alternatives considered**: 改 `OryxTool.execute` 签名带 Profile（课件明说"改工具接口签名代价太大"，否决）；Scope Bean（引入 Spring 依赖到 core 领域类，不必要）。
- **验证方式**: AgentServiceTest（含关键回归 assertNull after assertThrows）。

## D7. ContextLoader：无缓存 + 缺失分级

- **Decision**: 构造注入 `.oryxos` 根 `Path`。`load(Profile profile)` 每次调用重读文件（无任何缓存字段）：identity.prompt（Profile 内嵌，非文件）→ 按 `profile.bootstrap()` 顺序读根目录下同名文件（如 `AGENTS.md`），**缺失 → WARN 日志 + 跳过**；按 `profile.skills()` 读 `skills/{name}.md`，**缺失 → 抛 `IllegalStateException` 点名文件**（"人格悄悄丢了"是软故障，Skill 缺失是硬错误——课件铁律二）。返回拼接文本。日志参数过 `replace('\r','_').replace('\n','_')` 消毒。
- **Rationale**: TechSol §8.3 与课件铁律逐字对应；分级（Skill 报错 / Bootstrap WARN）来自课件"显式引用的文件缺失要报错、Bootstrap 缺失至少 WARN"。
- **Alternatives considered**: 缓存 + 文件监听（明确禁止——"每次组装都重新读文件、不缓存"）；Bootstrap 缺失也报错（课件只要求 WARN，Bootstrap 是可选增强不是 Agent 定义本体）。
- **验证方式**: ContextLoaderTest（@TempDir 改文件即生效、Skill 缺失抛错、Bootstrap 缺失 WARN——用 Logback ListAppender 断言）。

## D8. tool_invocations 存储：与 16 节 llm_calls 完全同构

- **Decision**: `schema.sql` 追加 `CREATE TABLE IF NOT EXISTS tool_invocations`（列按 CLAUDE.md §SQLite 核心表：id/session_id/tool_name/input_json/result_json/success/error_message/duration_ms/created_at + `idx_tool_invocations_session` 索引）；`ToolInvocation` JPA 实体（@PrePersist createdAt）；`ToolInvocationRepository extends JpaRepository`（`findBySessionId`）；`JpaToolInvocationAuditor implements ToolInvocationAuditor`——写入自吞 `RuntimeException` 记 ERROR 不阻断（16 节 D5 同口径）。
- **Rationale**: 宪法 V Day-One 写入；同构复用已验证的 16 节模式（@DataJpaTest + @TempDir SQLite 文件 + `sql.init.mode=always` + `ddl-auto=none`），测试基建零新增。
- **Alternatives considered**: Flyway（CLAUDE.md 允许但 16 节已选手工脚本，保持一致）；复用 LlmCall 表加 type 列（两表结构不同，且表名是已定字面量）。
- **验证方式**: ToolInvocationRepositoryTest 三连（保存查询/成败双态/schema 生效），抄 16 节 LlmCallRepositoryTest 模式。
