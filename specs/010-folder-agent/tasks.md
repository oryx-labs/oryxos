# Tasks: 插件化 Agent —— 一个目录定义一个会自己跑的 Agent

**Feature**: `specs/010-folder-agent` | **Branch**: `class-29`

**Input**: plan.md / research.md / data-model.md / contracts/internal-api.md / quickstart.md

约束：不新增第三方依赖、不新增数据表、无 `@Tag("integration")`；避开 Java 18+ 语法（增强 switch `default ->`、record 模式、pattern-matching switch）；测试方法名英文 + `@DisplayName` 保留课件中文守点；实现完成 = `mvn clean verify` 全绿 + 前序节回归绿。

---

## Phase 1: Setup

- [ ] T001 依赖存在性复核：在仓库根跑 `mvn dependency:tree | grep -i snakeyaml` 确认 SnakeYAML 已在锁定 BOM（`ProfileLoader` 已用），确认本节零新增第三方依赖；无 `pom.xml` 改动。

---

## Phase 2: Foundational（阻塞所有故事的共享改造）

- [ ] T002 [P] 新增 `AgentMarkdown` 于 `oryxos-core/src/main/java/io/oryxos/core/agent/AgentMarkdown.java`：`split(String) → Parsed(Map<String,Object> frontmatter, String body)`，识别 `---\n<yaml>\n---\n<body>`（SnakeYAML 解析 frontmatter），无 frontmatter 时空 Map + 全文当 body。伴随 `AgentMarkdownTest`（`oryxos-core/src/test/.../agent/AgentMarkdownTest.java`）：有/无 frontmatter、frontmatter 为空、正文含 `---` 等。
- [ ] T003 抽出可复用校验入口：`oryxos-core/src/main/java/io/oryxos/core/profile/ProfileLoader.java` 把 `parse(Path)` 的"Map→Profile+全字段校验"抽成 `static Profile fromMap(Map<String,Object> map, String source)`（抛 `ProfileValidationException`，**消息文案逐字不变**），`parse` 改为读文件后委托 `fromMap`；停解析 `skills` 键。保证 `ProfileLoaderTest`（16 节）文案断言不漂移。

### ⚠️ GATE-D3（软门禁：移除 `Profile.skills` 字段）——需用户在停点确认后才落

- [ ] T004 [GATE-D3] 移除 `Profile` record 的 `skills` 字段（`oryxos-core/.../profile/Profile.java`，canonical 12→11 参，含构造器防御性拷贝段同步），并**原子性**修全部消费/构造点使其编译通过：
  - 消费：`oryxos-core/.../context/ContextLoader.java`（删 `for (String skill : profile.skills())` 循环）、`oryxos-web/.../controller/ProfileApiController.java`（视图去 `p.skills()`）。
  - 构造点（`new Profile(...)` 去第 6 位实参）：`ProfileLoader.java` + 11 个测试 `ContextLoaderTest`、`PromptBuilderTest`、`ReActLoopTest`、`AgentSchedulerTest`、`AgentServiceTest`（oryxos-core）、`MockProviderFlowTest`（oryxos-boot）、`ProviderServiceTest`、`ProviderSmokeIT`（oryxos-provider）、`WebSmokeIT`、`AgentApiControllerTest`（oryxos-web）、`NotifyToolsTest`（oryxos-tool）。
  - **备选（若用户不批移除）**：保留 `skills` 为恒空字段——则本任务缩为"仅 `ContextLoader` 去消费、`ProfileLoader` 停填"，Profile 构造点不动。二选一在停点定死后执行。

---

## Phase 3: User Story 1 — 往目录区丢一个目录 = 定义并装上一个 Agent (P1) 🎯 MVP

**Goal**：扫 `.oryxos/agents/` 把每个目录派生成一个已登记 Agent；扫 N 得 N、不产生别的；缺必填点名、坏 Agent 不阻断。

**Independent Test**：放 1 个、再放 N 个合法 Agent 目录，`AgentLoader.loadAll()` 后注册表恰好出现对应数量、名字正确。

- [ ] T005 [P] [US1] `AgentLoaderTest`（`oryxos-core/src/test/.../agent/AgentLoaderTest.java`）：@DisplayName 保留课件守点——"目录拆 frontmatter/正文、认 scripts/skills/REFERENCE.md 资源"、"缺 name/provider → 报错点名（`ProfileValidationException` 消息含缺失项）"、"一坏一好目录 → 好的仍登记、坏的记错误不阻断"。英文方法名如 `deriveProfile_missingProvider_throwsNamedError`。
- [ ] T006 [P] [US1] `DeriveProfileTest`（`.../agent/DeriveProfileTest.java`）：frontmatter 各字段正确映射 `Profile`（provider/tools/notify_channels/settings 等）；**`schedules` 原样带进派生 Profile**（`scheduleCarriedIntoProfile`，定时来自 Agent 直接证据）。
- [ ] T007 [P] [US1] `AgentScanRegisterTest`（`.../agent/AgentScanRegisterTest.java`）：临时目录放 N 个 `AGENT.md` → `loadAll()` 后 `registry.all().size()==N` 且名字集合相符、**不产生别的东西**（`scanNDirs_yieldsNAgentsOnly`）。
- [ ] T008 [US1] 实现 `AgentLoader`（`oryxos-core/src/main/java/io/oryxos/core/agent/AgentLoader.java`）：ctor `(Path agentsDir, Set<String> knownProviders)`；`deriveProfile(agentDir)` 用 `AgentMarkdown.split` 拆 frontmatter → `ProfileLoader.fromMap`（同一异常同一消息）、绑定资源路径；`loadAll()` 遍历子目录、坏的记错误跳过不阻断、`tools` 引用未注册能力 WARN。让 T005–T007 转绿。
- [ ] T009 [US1] 装配层 `oryxos-cli/.../OryxOsRuntime.java`：启动扫 `.oryxos/agents/`（`AgentLoader.loadAll` → 得到 `ProfileRegistry`），替换原 `new ProfileLoader(oryxosRoot().resolve("profiles"), …).loadAll()` 接线（第 125 行）。
- [ ] T010 [P] [US1] 目录名迁移 `profiles → agents`：`oryxos-cli/.../command/InitCommand.java`（建的目录列表 `profiles`→`agents`、去 `skills`）、`StatusCommand.java`（`root.resolve("profiles")`→`agents`、glob `*.yaml`→按 agents 目录）、`ProfileCommand.java`（`PROFILES_DIR` 与读写路径→`.oryxos/agents`，列出各 Agent 目录）。

---

## Phase 4: User Story 2 — Agent 到点自己跑（定时来自 Agent） (P2)

**Goal**：带 `schedules` 的 Agent 到点无人触发自跑一轮；`registerProfile` 留可注销句柄。

**Independent Test**：给一个 Agent 配"几分钟后触发"，等它自动跑一轮；核对触发参数来自其自身配置、留有句柄。

- [ ] T011 [P] [US2] `AgentSchedulerRegisterTest`（`oryxos-core/src/test/.../agent/AgentSchedulerRegisterTest.java`）：`registerProfile` 后 `scheduledTasks` 含该任务句柄（`registerProfile_leavesCancellableHandle`）；cron/zone 来自 `Profile.schedules`（`cronComesFromProfileSchedules`）。
- [ ] T012 [US2] 改造 `oryxos-core/.../agent/AgentScheduler.java`：把 `registerAll()` 循环体抽成 `registerProfile(Profile)`；新增 `Map<String, ScheduledFuture<?>> scheduledTasks`（与 `taskLocks`/`taskStore` 并存），登记时留句柄；`registerAll()` 改为遍历 `ProfileRegistry` 调 `registerProfile`，保留每条 `try/catch` 跳非法 cron。让 T011 转绿。
- [ ] T013 [US2] 装配层 `OryxOsRuntime`：扫描后对有 `schedules` 的 Profile 调 `AgentScheduler.registerProfile`（沿用既有 `@Bean(initMethod="registerAll")` 或显式遍历），确保钟推与人推同一 `AgentService.process` 入口不变。
- [ ] T014 [US2] 回归 `oryxos-core/src/test/.../agent/AgentSchedulerTest.java`（25 节）：`registerAll` 改造 + 新句柄表不破原有 6 个测试；补断言 registerAll 后句柄表非空。

---

## Phase 5: User Story 3 — 渐进式披露：正文进 prompt、子资源用到才取 (P2)

**Goal**：AGENT.md 正文进 system prompt；子指令/参考/脚本不预载；改盘上正文后再 load 反映新正文（无缓存）。

**Independent Test**：造带子指令与脚本的 Agent，`ContextLoader.load` 结果含正文、不含子资源内容；改正文后再 load 立即反映。

- [ ] T015 [P] [US3] `ProgressiveDisclosureTest`（`oryxos-core/src/test/.../context/ProgressiveDisclosureTest.java`）：@DisplayName 守点——"AGENT.md 正文进 system prompt"、"子指令/参考/脚本内容不预载（load 结果不含 `skills/`/`REFERENCE.md`/脚本文本）"、"改盘上正文后再 load 反映新正文=无缓存"（`bodyEditTakesEffectWithoutRestart`）。
- [ ] T016 [US3] 改造 `oryxos-core/.../context/ContextLoader.java`：ctor 保持 `(Path oryxosRoot)`；`load(profile)` 保留 `identity.prompt` 与 `bootstrap` 注入，**新增**"现读 `oryxosRoot/agents/<profile.name()>/AGENT.md`、用 `AgentMarkdown` 拆掉 frontmatter、注入正文"，**删除** `skills` 循环（T004 已删字段则消费点已消失，此处确保正文注入路径）；保持"每次现读、无缓存"。让 T015 转绿。
- [ ] T017 [US3] 回归 `oryxos-core/src/test/.../context/ContextLoaderTest.java`（17 节）：删"skill 全文注入 / 缺 skill 文件抛错"断言（字段已移除）；新增"AGENT.md 正文注入 + 无缓存改正文即时生效"；保留 Bootstrap 缺失 WARN、Bootstrap 无缓存回归。
- [ ] T018 [US3] 回归 `oryxos-core/src/test/.../agent/PromptBuilderTest.java`（17 节）：system 段断言随"只含 AGENT.md 正文 + Bootstrap（无能力索引/无 skill 全文）"调整。

---

## Phase 6: User Story 4 — 运行时把一个 Agent 登记/注销/查询 (P3)

**Goal**：运行期 register/remove/exists；运行时与启动同一套校验（同一异常同一消息）。

**Independent Test**：运行时 register 后立即 get 可见；非法配置经运行时与启动两路径抛出的异常类型+消息完全相等。

- [ ] T019 [P] [US4] `ProfileRegistryRuntimeTest`（`oryxos-core/src/test/.../profile/ProfileRegistryRuntimeTest.java`）：`register` 后立即 `get` 可见（`registerThenGet_visibleImmediately`）；非法配置经运行时 `register`（走 `fromMap` 校验）与启动路径抛出的 `ProfileValidationException` 类型+message 完全相等（`runtimeAndStartup_sameExceptionSameMessage`）。
- [ ] T020 [US4] 改造 `oryxos-core/.../profile/ProfileRegistry.java`：不可变 `Map.copyOf` → `ConcurrentHashMap`；新增 `register(Profile)`/`remove(String)`/`exists(String)`，保留 `get`/`all`；运行时登记复用 `ProfileLoader.fromMap` 校验路径（由调用方 AgentLoader 保证同一异常）。让 T019 转绿；不破既有依赖 `ProfileRegistry(Map)` 的构造点（保留该 ctor 或 initial 拷贝进并发 Map）。

---

## Phase 7: Polish & 交付物 & 跨节回归

- [ ] T021 [P] 产出示例 Agent 目录 `my-agent/.oryxos/agents/daily-reconcile/`（严格按第29节课件 §1.3–1.4 规格）：`AGENT.md`（frontmatter profile + 4 步正文）、`scripts/reconcile.py`（确定性比对输出差异 JSON、纯标准库）、`skills/report-format.md`（分级规范）、`REFERENCE.md`（字段字典/已知可接受差异）。作手动路径参照物。
- [ ] T022 [P] 迁移遗留 `.oryxos/profiles` 引用：删/迁 `my-agent/.oryxos/profiles/weather.yaml` → `my-agent/.oryxos/agents/weather/AGENT.md`；`oryxos-web` 测试 `WebSmokeIT`/`AgentApiControllerTest` 等对 profiles 目录/字段的引用一并迁 agents（与 T004 构造点修改协同，避免二次编译红）。
- [ ] T023 全量门禁 + 跨节回归：`mvn clean verify` 全绿（P3C/SpotBugs/FindSecBugs/PMD）；前序节测试回归绿（17 `ContextLoaderTest`/`PromptBuilderTest`、25 `AgentSchedulerTest`、16 `ProfileLoaderTest`）；H4 六条全局不变量自查。

---

## Dependencies & 执行顺序

- **Setup(T001)** → **Foundational(T002,T003,T004/GATE-D3)** → 各故事。
- T002(AgentMarkdown)、T003(fromMap) 阻塞 US1 的 AgentLoader(T008)。
- **T004(GATE-D3)** 阻塞所有 `new Profile(...)` 的测试编译（T005–T007、T011、T015、T019 等）与 T022 —— **须在停点确认后先行**（或走备选保留恒空字段路径）。
- US1(P1) 是 MVP，独立可测。US2/US3/US4 各自独立；US2 定时与 US3 正文/披露互不依赖；US4 运行时注册复用 T003 的 `fromMap`。
- ContextLoader(T016) 属 US3；US2 的定时不依赖它。

## Parallel 机会

- Foundational：T002 与 T003 可并（不同文件）。
- 各故事内 harness 测试 `[P]`（不同测试文件）可并写：T005/T006/T007、T011、T015、T019、T021/T022。
- 实现任务多为改同一批 core 类，串行为宜。

## MVP

**US1（Phase 3）** = 最小可交付：往 `.oryxos/agents/` 丢一个目录就得到一个已登记 Agent。US2/US3/US4 增量叠加"自跑 / 渐进式披露 / 运行时注册"。

## 格式校验

所有任务均 `- [ ] Txxx [P?] [US?] 描述 + 文件路径`；Setup/Foundational/Polish 无 Story 标签，US 阶段带 `[US1..4]`。
