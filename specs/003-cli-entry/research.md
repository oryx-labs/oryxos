# Research: CLI——OryxOS 的命令行入口

**Date**: 2026-07-10 | **Feature**: [spec.md](./spec.md)

各决策的"验证方式"注明本地实核手段（H3：写码前先核实，不臆造 API）。

## D1. 命令树形态：Picocli 根命令 + 嵌套 profile 子命令

- **Decision**: `OryxOsCli` 为 `@Command(name="oryxos", subcommands={...}, mixinStandardHelpOptions=true)` 根；`main` 里 `System.exit(new CommandLine(new OryxOsCli()).execute(args))`。profile 的 list/create/show/delete 作为 `ProfileCommand` 的嵌套 `subcommands`；其余命令各一个类。命令总面：init、status、chat、serve、gateway、profile(list/create/show/delete)、provider list、tool list、session list——课件口径 12 个。
- **Rationale**: 课件明文"别自己解析 args，用 Picocli"；`execute()` 统一处理帮助/报错/退出码（FR-001 统一报错）。
- **Alternatives considered**: 12 个平铺类（profile 四件语义上是同组动作，Picocli 嵌套子命令即为此设计）；Spring Boot + picocli-spring-factory（轻命令就得起 Spring，违 FR-003）。
- **验证方式**: javap 已实核 `CommandLine(Object)`、`execute(String...)`、`@Command.subcommands()`、`mixinStandardHelpOptions()`、`@Option.defaultValue()`（4.7.6 本地 jar）。

## D2. 轻重分流的实现形态

- **Decision**: 轻命令（init/status/profile 四件/provider list/tool list/session list）在 `run()` 里直接用 `java.nio.file.Files` / 纯 JDBC（`DriverManager.getConnection("jdbc:sqlite:...")` 只读查 sessions 表）完成，全程不 import Spring；重命令（chat/serve/gateway）在 `run()` 里 `SpringApplication.run(OryxOsRuntime.class)` 拿 `ConfigurableApplicationContext` 再取 Bean。分流标准照课件：要调模型/跑引擎才起 Spring。
- **Rationale**: 课件坑二（启动 2~4 秒 vs 秒回）；`provider list` 读 boot `application.yml` 的 `oryxos.providers` 段（SnakeYAML 直读）而非起容器。
- **Alternatives considered**: lazy-init 的单一 Spring 入口（仍有秒级 JVM+容器代价，且违课件明文分流）；GraalVM native（扩展阶段话题）。
- **验证方式**: sqlite-jdbc 与 SnakeYAML 均在依赖树（16 节已用）；人工项验证轻命令秒回。

## D3. 重命令装配：OryxOsRuntime（课件坑四的正面解）

- **Decision**: `oryxos-cli` 新增 `OryxOsRuntime`：`@SpringBootApplication(scanBasePackages="io.oryxos")` + **显式** `@EnableJpaRepositories(basePackages="io.oryxos.storage")` + `@EntityScan(basePackages="io.oryxos.storage")`；配套 `@Bean` 显式装配运行链——`ProvidersProperties`→`ProviderChatModelFactory.build()`→providerMap→`SpringAiProviderServiceImpl`（宪法 III）、`JpaLlmCallAuditor`/`JpaToolInvocationAuditor`、`ProfileLoader.loadAll()`→`ProfileRegistry`、`ContextLoader(Path.of(".oryxos"))`、`PromptBuilder`、`ToolExecutor(Map.of(), auditor)`（工具 20 节接线）、`ReActLoop`、`JpaSessionManager`、`AgentService`。datasource/JPA 配置经 `application.yml`（classpath 取自 boot 同款键，cli 模块自带一份精简 yml：datasource sqlite、ddl-auto=none、sql.init.mode=always、autoconfigure exclude DashScope）。
- **Rationale**: 课件坑四明文：`scanBasePackages` 不带动 `@EnableJpaRepositories`/`@EntityScan`，跨模块必须显式声明，否则 "Found 0 JPA repository interfaces" 带病运行。16/17 节的类全是纯 POJO 无 Spring 注解，本节装配层用 `@Bean` 显式接线正好保持它们零框架依赖。
- **Alternatives considered**: 复用 boot 的 `OryxOsApplication`（boot→cli 依赖已存在，cli 反依赖 boot 成环，不可行）；给 core 类加 `@Component`（领域类沾框架，且扫描全靠魔法，违"显式装配"精神）。
- **验证方式**: `@EnableJpaRepositories`/`@EntityScan` 为 spring-boot/spring-data 标准注解（16 节测试已用 @DataJpaTest 同族机制）；chat 人工冒烟看启动日志 "Found N JPA repository interfaces" N>0。

## D4. CliChannel：chat 交互循环（课件骨架逐行同构）

- **Decision**: `oryxos-channel-cli` 的 `CliChannel`：构造注入 `AgentService`、`SessionManager`、输入流/输出流（默认 `System.in`/`System.out`，测试可替换但本节不写壳测试）。`run(String profileName, String userId)`：`sessionManager.getOrCreate("cli", userId, profileName)` → 循环 `print("> ")`、`readLine`、null(EOF)/`/quit`(trim) 退出、空行跳过、`agentService.process(session, line)`、打印回复。`ChatCommand` 从 Spring 上下文取 `CliChannel` 并传 `--profile`（默认 "default"）与系统用户名。
- **Rationale**: 课件骨架逐行对应；channel 字面量 `"cli"` 只在 CliChannel 提供三元组处出现，不拼 id。EOF 处理为 spec Edge Case。
- **Alternatives considered**: JLine 行编辑（扩展体验，核心阶段 Scanner/BufferedReader 足够）。
- **验证方式**: 纯 JDK API；人工冒烟。

## D5. SessionManager 补全与 id 公式

- **Decision**: core 接口补全为三方法：`Session getOrCreate(String channel, String userId, String profileName)`、`Optional<Session> get(String sessionId)`、`void save(Session session)`。id 公式 `channel + ":" + userId + ":" + profileName`（clarify 既定默认），**私有方法 `sessionId(...)` 只存在于 `JpaSessionManager`**——全库唯一拼接点（H4④）。
- **Rationale**: 课件明文"对外三个方法"；17 节前向接口（仅 save）本节按预告补全，17 节 `AgentService` 只调 `save`，加方法不破坏既有调用（二进制兼容）。
- **Alternatives considered**: id 用 UUID+三元组唯一索引（多一列查询、id 不可读，且课件明文"channel+user+profile 联合生成"）。
- **验证方式**: SessionManagerTest 幂等/隔离断言 + `grep -rn '":" +' --include=*.java` 核查拼接只此一处。

## D6. 持久化映射：storage.Session 实体 + messages_json

- **Decision**: `io.oryxos.storage.Session` 实体：`@Id String sessionId` + profileName/channel/userId/messagesJson(TEXT)/status/createdAt/lastActiveAt/archivedAt（`@PrePersist` 补 createdAt、status 缺省 "active"）。`JpaSessionManager` 用 Jackson `ObjectMapper` 把 `List<Message>`（role/content/toolName record）整体序列化进 `messages_json`；恢复时 `readValue(..., new TypeReference<List<Message>>(){})` 经 core Session 恢复构造器还原。`getOrCreate`：`repository.findById(id)` 命中→恢复；未命中→建实体（status=active）落库 + 新领域 Session。`save`：序列化覆盖 messagesJson、刷 lastActiveAt。archived 本节只建列不写（26 节 DELETE 端点）。同名冲突处理：JpaSessionManager 内对 core 类型用全限定名或 import 别居其一（core Session import、实体全限定），一处注释说明。
- **Rationale**: 课件明文"整体序列化成 JSON 存一列，不按条拆表"；Message 是 record，Jackson 对 record 的构造器绑定 Boot 3.x 开箱即用。
- **Alternatives considered**: 实体改名 SessionEntity（偏离课件字面量"`Session` 实体"）；@Converter 属性转换器（多一层魔法，显式序列化更可讲）。
- **验证方式**: SessionRepositoryTest 回读断言（含三类 role 消息顺序）；Jackson record 支持编译+测试即证。

## D7. core Session 恢复构造器（17 节预告改造点）

- **Decision**: `Session(String sessionId, String profileName, List<Message> restored)`——把历史消息按序填入内部列表；既有双参构造器与三个 append 不动。
- **Rationale**: 17 节 research D2 明文"18 节补全"；恢复路径没有它就只能反射或重放 append（都更糟）。
- **Alternatives considered**: `static Session restore(...)` 工厂（等价，构造器与既有形态更一致）。
- **验证方式**: SessionRepositoryTest 重启恢复断言直接消费它。

## D8. sessions DDL 与 boot 配置纠正

- **Decision**: `schema.sql` 追加 `CREATE TABLE IF NOT EXISTS sessions`（session_id VARCHAR PRIMARY KEY、profile_name/channel/user_id VARCHAR NOT NULL、messages_json TEXT、status VARCHAR NOT NULL、created_at/last_active_at TIMESTAMP、archived_at TIMESTAMP 可空）+ `idx_sessions_profile`（session list 按 profile 看）。boot `application.yml`：`ddl-auto: create-drop`→`none`、新增 `spring.sql.init.mode: always`（脚手架遗留纠正，否则重启毁数据、违宪法 VIII）。
- **Rationale**: 16/17 节同口径；主键即三元组拼接结果，天然幂等兜底（并发 getOrCreate 撞主键即"已存在"）。
- **Alternatives considered**: 唯一索引三列+代理主键（id 公式已是唯一权威，重复约束）。
- **验证方式**: SessionRepositoryTest 用手工脚本建表（sql.init.mode=always）存读三连。
