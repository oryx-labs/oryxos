# Tasks: CLI——OryxOS 的命令行入口

**Input**: Design documents from `/specs/003-cli-entry/`（plan.md / research.md D1~D8 / data-model.md / contracts/cli.md / quickstart.md）

**Tests**: 课件"验收 harness"只点名会话层两个测试类（落 oryxos-storage）；CLI 壳按课件明示不写单测（成本大于收益，留人工清单）。测试方法名英文 + `@DisplayName` 保课件中文原名。

**Organization**: 会话层（US2/US3，地基）→ chat 链路（US1）→ 命令面与分流（US4）→ 装配校验与配置纠正（US5）→ Polish。

## Phase 1: Setup

- [x] T001 记录改造前基线：仓库根执行 `mvn test -pl oryxos-core,oryxos-provider,oryxos-storage -am -q` 确认 16/17 节测试全绿

## Phase 2: Foundational（core 契约补全 + 存储地基，阻塞全部故事）

- [x] T002 core 契约补全（17 节预告改造点）：`oryxos-core/src/main/java/io/oryxos/core/session/SessionManager.java` 加 `Session getOrCreate(String channel, String userId, String profileName)` 与 `Optional<Session> get(String sessionId)`；`Session.java` 加恢复构造器 `Session(String sessionId, String profileName, List<Message> restored)`（既有构造器与 append 三兄弟不动）
- [x] T003 [P] `oryxos-storage/src/main/resources/schema.sql` 追加 `sessions` DDL（session_id VARCHAR PRIMARY KEY、profile_name/channel/user_id NOT NULL、messages_json TEXT、status NOT NULL、created_at/last_active_at/archived_at、idx_sessions_profile 索引）
- [x] T004 [P] pom 依赖补齐：`oryxos-cli/pom.xml` +oryxos-channel-cli/oryxos-provider/oryxos-storage/spring-boot-starter/spring-boot-starter-data-jpa/sqlite-jdbc/snakeyaml；确认 storage 经 core 传递可用 jackson-databind（JpaSessionManager 序列化用）

## Phase 3: US2+US3 会话层——幂等/隔离/落库恢复 (P1，harness 先行)

**Goal**: 三元组口径一次钉死；sessions 表能存能读、跨重启恢复。
**Independent Test**: `mvn test -pl oryxos-storage -am -Dtest='SessionManagerTest,SessionRepositoryTest'` 全绿。

- [x] T005 [US2] SessionManagerTest：`oryxos-storage/src/test/java/io/oryxos/storage/SessionManagerTest.java`（@DataJpaTest + @TempDir SQLite 文件库，16 节模式）——①**关键回归** `sameTriple_everyGetOrCreateReturnsSameSession` `@DisplayName("同一三元组_历次getOrCreate都是同一个Session")`：两次 `getOrCreate("cli","wang","default")` 断言 `assertEquals(first.sessionId(), second.sessionId())` + `getOrCreate("web","wang","default")` 断言 `assertNotEquals`（断言逻辑照课件逐条保真）；②user/profile 任一不同 → 不同会话；③id 格式为 `cli:wang:default`（clarify 既定）；④getOrCreate 未命中时落一条 status=active 记录
- [x] T006 [US3] SessionRepositoryTest：`oryxos-storage/src/test/java/io/oryxos/storage/SessionRepositoryTest.java`——①手工建表脚本建出的表能存能读；②含 user/assistant/tool 三类消息的会话 save 后经 getOrCreate 回读，消息完整顺序不变（messages_json 序列化回读）；③模拟重启：新建 ObjectMapper/Manager 实例重查（同一库文件）历史还在；④新会话零消息正常保存恢复；⑤save 刷新 last_active_at
- [x] T007 [US3] 存储两件：`oryxos-storage/src/main/java/io/oryxos/storage/Session.java`（JPA 实体 @Table("sessions")，@PrePersist 补 createdAt、status 缺省 active；与 core 领域对象同名不同包）+ `SessionRepository.java`（JpaRepository<Session,String>）
- [x] T008 [US2] JpaSessionManager：`oryxos-storage/src/main/java/io/oryxos/storage/JpaSessionManager.java`——implements core SessionManager；私有 `sessionId(channel,userId,profileName)` = `channel + ":" + userId + ":" + profileName`（全库唯一拼接点，H4④）；getOrCreate 命中→Jackson 反序列化恢复领域 Session、未命中→新建 active 记录；save 序列化覆盖 messages_json + 刷 last_active_at；archived 不写入
- [x] T009 [US3] 阶段门禁：`mvn test -pl oryxos-storage -am` 全绿（含 16/17 节该模块回归），红了当场修

## Phase 4: US1 chat 链路 (P1)

**Goal**: 终端多轮对话跑通：CliChannel 交互循环 + 重命令 Spring 装配。
**Independent Test**: 编译 + storage 测试已证会话链；交互体验属人工项。

- [x] T010 [US1] CliChannel：`oryxos-channel-cli/src/main/java/io/oryxos/channel/cli/CliChannel.java`——构造注入 AgentService/SessionManager；`run(profileName, userId)`：`getOrCreate("cli", userId, profileName)` → 循环 `> ` 提示、readLine、EOF/`/quit`(trim) 退出、空行跳过、`agentService.process(session, line)`、打印回复（课件骨架逐行同构）
- [x] T011 [US1] OryxOsRuntime 装配：`oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`——@SpringBootApplication(scanBasePackages="io.oryxos") + **@EnableJpaRepositories(basePackages="io.oryxos.storage") + @EntityScan(basePackages="io.oryxos.storage")**（课件坑四）+ @Bean 显式装配全链（研发决策 D3：providerMap→SpringAiProviderServiceImpl→双 auditor→ProfileRegistry→ContextLoader→PromptBuilder→ToolExecutor(Map.of())→ReActLoop→JpaSessionManager→AgentService→CliChannel）；伴随 `oryxos-cli/src/main/resources/application.yml`（sqlite datasource、ddl-auto=none、sql.init.mode=always、exclude DashScopeAutoConfiguration、banner off）
- [x] T012 [US1] ChatCommand：`oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java`——@Command(name="chat")，@Option(names="--profile", defaultValue="default")；SpringApplication.run(OryxOsRuntime) → 取 CliChannel → `run(profileName, System.getProperty("user.name"))`；Profile 不存在时点名报错退出不进循环
- [x] T013 [US1] 阶段门禁：`mvn -q compile -pl oryxos-cli,oryxos-channel-cli -am` 通过

## Phase 5: US4 命令面与轻重分流 (P2)

**Goal**: 12 个子命令齐全；轻命令零 Spring。
**Independent Test**: 编译过 + fat jar `--help` 冒烟（Polish 阶段脚本化执行）。

- [x] T014 [US4] OryxOsCli 根：`oryxos-cli/src/main/java/io/oryxos/cli/OryxOsCli.java`——@Command(name="oryxos", mixinStandardHelpOptions=true, subcommands={12 个})；`main` = `System.exit(new CommandLine(new OryxOsCli()).execute(args))`
- [x] T015 [P] [US4] InitCommand + StatusCommand：`oryxos-cli/src/main/java/io/oryxos/cli/command/`——init 用 Files 建 `.oryxos/`（profiles/skills/memory/sessions/logs + AGENTS.md/SOUL.md/USER.md 占位），幂等不覆盖；status 检查工作区/配置/oryxos.db 存在性输出摘要（纯文件操作，零 Spring）
- [x] T016 [P] [US4] ProfileCommand（嵌套 list/create/show/delete）：`oryxos-cli/src/main/java/io/oryxos/cli/command/ProfileCommand.java`——list 列 `.oryxos/profiles/*.yaml` 文件名；create 写最小模板 YAML（不覆盖已有）；show 打印文件内容；delete 删除（不存在则报错点名）；零 Spring
- [x] T017 [P] [US4] ProviderListCommand + ToolListCommand + SessionListCommand：`oryxos-cli/src/main/java/io/oryxos/cli/command/`——provider list 用 SnakeYAML 直读 classpath application.yml 的 oryxos.providers 段列 name/baseUrl；tool list 输出内置工具占位清单（注明 20 节接 ToolRegistry）；session list 纯 JDBC 只读查 sessions 表（session_id/profile/status/last_active_at），库文件不存在→"暂无会话"（零 Spring）
- [x] T018 [P] [US4] ServeCommand + GatewayCommand 启动骨架：`oryxos-cli/src/main/java/io/oryxos/cli/command/`——都 SpringApplication.run(OryxOsRuntime) 常驻 + 打印说明（serve：REST 端点 26 节接线；gateway：多通道挂载扩展阶段），--port 选项透传 server.port
- [x] T019 [US4] fat jar 入口：`oryxos-boot/pom.xml` spring-boot-maven-plugin mainClass 改为 `io.oryxos.cli.OryxOsCli`（boot 依赖 cli 已有；OryxOsApplication 保留供 26 节）
- [x] T020 [US4] 阶段门禁：`mvn -q compile -pl oryxos-cli -am` + 全模块编译通过

## Phase 6: US5 装配校验与 boot 配置纠正 (P2)

- [x] T021 [US5] boot 配置纠正（独立任务）：`oryxos-boot/src/main/resources/application.yml`——`ddl-auto: create-drop` → `none`，新增 `spring.sql.init.mode: always`（脚手架遗留纠正，宪法 VIII；重启不再毁数据）
- [x] T022 [US5] 装配完整性核验：确认 SessionManagerTest/SessionRepositoryTest（@DataJpaTest 显式装配）绿即 FR-008 自动化部分达成；启动日志 "Found N JPA repository interfaces" N>0 归人工清单（quickstart 3）

## Phase 7: Polish & 收尾

- [x] T023 全仓硬门禁：`mvn clean verify` 全绿（含 Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs），静态检查红了修实现不改规则
- [x] T024 fat jar 冒烟（可自动化部分）：`mvn -pl oryxos-boot -am package -DskipTests` 后脚本跑 `java -jar ... --help` 与 12 个子命令 `--help`、`init`、`profile list`，记录输出与耗时（轻命令秒回证据）
- [x] T025 H4 六条全局不变量自查 + 课件"本节交付物"逐项 ls/grep 存在性核对 + id 拼接唯一点 grep 证据
- [x] T026 验收报告：`specs/003-cli-entry/acceptance-report.md`（六项证据 DoD + 剩余人工项清单：chat 真模型多轮对话与 /quit、Found N>0 目检、三模式共享存储、轻命令秒回体感）

## Dependencies

- Phase 2（T002~T004）阻塞全部；T003/T004 可并行。
- US2/US3（T005~T009）：T005/T006 先于 T007/T008（harness 先行，允许伴随）；T009 收口。
- US1（T010~T013）依赖 Phase 2 + T008（JpaSessionManager 装配进 Runtime）。
- US4（T014~T020）：T015~T018 四组互不同文件可并行；T014 先行（根命令引用子命令类，可先建骨架后补 subcommands 数组）。
- US5 T021 独立可随时做；T022 依赖 T009。
- Polish 依赖全部。

## Implementation Strategy

- MVP = Phase 2 + US2/US3 + US1（终端能跟 Agent 说上话 + 会话口径钉死）；US4 补齐命令面；US5 收装配与配置债。
- 每阶段结束跑阶段门禁，红了当场修不攒账。
- 并行机会：T003∥T004；T015∥T016∥T017∥T018。
