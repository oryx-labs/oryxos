# Research: 动态管理 Agent —— 一句话生成、上传即上线

技术栈固定、终态模型已确认；一个实现层缺口（生成用哪个 provider/model）已在 clarify 定死。下面记录实现前必须定死的设计决策。

## D1：三录入一段代码 —— AgentLifecycleService.register(agentDir)

- **Decision**：`register(Path agentDir)` = `agentLoader.deriveProfile(agentDir) → profileRegistry.register(profile) →（profile.schedules() 非空）agentScheduler.registerProfile(profile)`，返回该 `Profile`。API `create` 写完目录调它、`WorkspaceWatcher` 变更事件调它；启动扫描仍走 29 节 `AgentLoader.loadAll`（同款派生 + 注册）。
- **Rationale**：FR-009 / 关键回归"三录入行为一模一样"——唯一保证是**同一段代码**。29 节已把 `deriveProfile`/`register`/`registerProfile` 立好，本节只编排。
- **Alternatives considered**：API 与 watcher 各写一套注册 —— 必然行为漂移，否决。

## D2：create 回滚 / delete 时序 / update 改定时

- **Decision**：
  - `create`：`profileRegistry.exists(name)` 为真 → 第一步抛 `IllegalArgumentException`（400），**不写目录**；否则 `agentStore.write(name, md)` → `try { register(dir) } catch { agentStore.delete(dir); throw }`。
  - `delete`：恒序 `agentScheduler.unregisterProfile(profile) → profileRegistry.remove(name) → agentStore.archive(name)`；找不到 → `ResourceNotFoundException`（404）。
  - `update`：覆写目录 `AGENT.md`；若 `schedules` 变 → 先 `unregisterProfile(old)` 再 `registerProfile(new)`（重新 deriveProfile）。
- **Rationale**：关键回归①（回滚不留半个）②（删除时序）。顺序反了（先 remove 后 unregister）会在窗口期 cron 触发拿不到 Profile 空指针——`InOrder` 钉死。
- **Alternatives considered**：用 Spring `@Transactional` 回滚 —— 文件系统不在事务里，无效，只能手动补偿删除。

## D3：一句话生成 —— 配置默认 provider/model + 既有 chat（clarify 已定）

- **Decision**：新增 `oryxos.generate.provider` + `oryxos.generate.model`（provider 缺省取 `oryxos.providers` 第一个）。`generate(sentence)`：
  1. 构造"生成用 `Profile`"：`provider = new ProviderRef(配置provider, 配置model, null)`，identity/tools 空。
  2. `ProviderRequest.of(AGENT_AUTHOR_PROMPT + "\n\n用户需求：" + sentence)`（`AGENT_AUTHOR_PROMPT` = OryxOS AGENT.md 格式说明常量）。
  3. `providerService.chat("generate:" + 随机/固定标签, 生成Profile, req).text()`（落 `llm_calls`）。
  4. 用 `AgentMarkdown.split` + `agentLoader.deriveProfile`（对临时目录）或等价校验产出能否解析成合法定义；非法 → `IllegalArgumentException`（400，可读原因）。
  5. 返回文本，**不落盘、不注册**。
- **Rationale**：`ProviderService` **无 `complete`**（H3 核实），只有 `chat(sessionId, Profile, ProviderRequest)`；`chat` 按 `profile.provider().name()+model()` 路由（model 真传 API），故需一对确定的 (provider, model)。系统无全局默认 → 配置键（clarify 用户选"新增默认配置键"）。生成也是 LLM 调用，落审计（宪法五）。
- **Alternatives considered**：复用第一个已注册 Agent 的 provider（零 Agent 时不可用）、请求体带 provider/model（把选择推给前端）—— 用户选了配置键方案。

## D4：错误码对齐既有（H3 核实）

- **Decision**：400 → 既有 `IllegalArgumentException`；404 → 既有 `io.oryxos.web.error.ResourceNotFoundException`；均由 26 节 `GlobalExceptionHandler` 已映射。统一 `ApiResponse` 信封。
- **Rationale**：课件用的 `InvalidRequestException`/`AgentNotFoundException` 在代码里不存在；复用既有映射不新造类型（软门禁：不发明新对外概念）。
- **Alternatives considered**：新建 `InvalidRequestException` —— 多余，`IllegalArgumentException` 已映射 400，否决。

## D5：AgentScheduler.unregisterProfile（29 节类加新方法，课件列明的改造点）

- **Decision**：`public void unregisterProfile(Profile profile)`：遍历 `profile.schedules()`，对每个 `sc.id()` 从既有私有 `scheduledTasks` 取 `ScheduledFuture` 调 `cancel(false)`、再 `scheduledTasks.remove(id)`；不动 `taskLocks`、不动 `taskStore`（本节只管注销触发，任务状态历史留库可追溯）。
- **Rationale**：FR-006/FR-007 —— 删除 / 改定时要真停掉 cron。29 节已留 `scheduledTasks` 句柄，本节兑现。`cancel(false)` 不打断正在跑的一次（配合 `taskLocks` 的重叠保护）。
- **Alternatives considered**：`cancel(true)` 强杀正在跑的任务 —— 可能中断一次正常执行，否决。

## D6：防目录穿越（文件浏览唯一安全要点）

- **Decision**：`WorkspaceApiController.file(path)`：`root = oryxosRoot.toAbsolutePath().normalize()`；`target = root.resolve(path).normalize()`；`if (!target.startsWith(root)) throw IllegalArgumentException`（400）；再校验是常规文件、只读返回文本。`tree` 只列 `agents/` 与 `archive/`。
- **Rationale**：FR-008 / 关键回归④。`../../etc/passwd`、绝对路径越界都被 `startsWith(root)` 挡。
- **Alternatives considered**：黑名单 `..` 字符串匹配 —— 易被编码绕过，`normalize+startsWith` 是标准正解。

## D7：WorkspaceWatcher（实时监听）

- **Decision**：JDK `java.nio.file.WatchService` 注册在 `.oryxos/agents/`（监 `ENTRY_CREATE`/`ENTRY_MODIFY`/`ENTRY_DELETE`）。`start()` 起 daemon 线程（cli `@Bean(initMethod="start")`，同 `ThreadPoolTaskScheduler`）跑事件循环；循环把每个事件转成 `handleChange(Path agentDir, WatchEvent.Kind kind)`——CREATE/MODIFY → `lifecycle.register(agentDir)`、DELETE → 注销；`handleChange` 单个坏目录 `try/catch` 记 WARN 跳过。`handleChange` 与循环分离 → 单测直接调它、不依赖真实文件系统事件时序。
- **Rationale**：FR-002 / 关键回归③"丢目录即上线"。守护线程是基础设施（宪法七允许，同 25 节 scheduler），非请求链路异步。`WatchService` 是 JDK 标准库，无新依赖。事件用 `kind() == ENTRY_DELETE` 等值比较，不用 switch 模式匹配（P3C 门禁）。
- **Alternatives considered**：轮询目录 mtime —— 延迟高、耗 CPU；第三方文件监听库 —— 违"不新增依赖"，否决。

## 依赖核实

- 无新增第三方依赖：`WatchService` 是 JDK；生成走既有 `ProviderService`；DTO/端点是 web 层普通类。
- 无新增数据表、无 schema 变更（generate 落既有 `llm_calls`；archive 是文件移动）。
- 新增配置键 `oryxos.generate.provider/model`（clarify 用户批准）——非数据表、非第三方依赖。
