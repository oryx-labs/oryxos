# Implementation Plan: CLI——OryxOS 的命令行入口

**Branch**: `class-18`（用户指定） | **Date**: 2026-07-10 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-cli-entry/spec.md`

## Summary

Picocli 命令行入口 `OryxOsCli` 挂 12 个子命令，轻重分流（轻命令纯文件/JDBC 直读秒回；chat/serve/gateway 才起 Spring）；`CliChannel` 实现 chat 交互循环（读—转交 `AgentService.process`—打印，`/quit` 退出）；会话层本节钉死：core `SessionManager` 接口补全三方法，storage 交付 `sessions` 表 + JPA 实体 + `JpaSessionManager`（session_id 拼接唯一发生地，`channel:user:profile`）；重命令装配显式声明 `@EnableJpaRepositories`/`@EntityScan`（课件坑四）。

## Technical Context

**Language/Version**: Java 21（虚拟线程；CLI 交互循环同步阻塞——宪法 VII）

**Primary Dependencies**: Picocli 4.7.6（根 pom 已锁，API 经 javap 实核：`new CommandLine(obj).execute(args)`、`@Command(name/subcommands/mixinStandardHelpOptions)`、`@Option(names/defaultValue)`）；Spring Boot 3.3.5（仅重命令启动）；Jackson（messages_json 序列化，Boot BOM 内）；sqlite-jdbc（session list 轻读）

**Storage**: SQLite + Spring Data JPA；`sessions` 表走手工 `schema.sql` 追加（宪法 VIII）；轻命令 `session list` 用纯 JDBC 只读查询（不起 Spring）

**Testing**: JUnit 5；`SessionManagerTest`/`SessionRepositoryTest` 落 oryxos-storage（@DataJpaTest + @TempDir SQLite 文件 + `sql.init.mode=always` + `ddl-auto=none`，16 节已验证模式）；CLI 壳不写单测（课件明示成本大于收益，留人工清单）

**Target Platform**: JVM 21 终端（macOS/Linux）；fat jar 由 oryxos-boot 打包

**Project Type**: Maven 多模块（本节触及 oryxos-cli、oryxos-channel-cli、oryxos-core、oryxos-storage、oryxos-boot 配置）

**Performance Goals**: 轻命令秒级返回（不启动 Spring 上下文）；重命令接受 2~4 秒启动

**Constraints**: CLI 层零 Agent 逻辑；session_id 只在 JpaSessionManager 拼接（H4④，本节起该不变量有了真正的落点）；测试方法名英文 + @DisplayName 中文原名；避开 Java 18+ 语法形态；日志参数字符形态消毒

**Scale/Scope**: 12 个命令类 + 1 主入口 + 1 装配配置（cli）；1 交互通道（channel-cli）；接口补全 + 恢复构造器（core）；实体/Repository/Manager 实现 + DDL（storage）；2 个测试类；1 处 boot 配置纠正

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节符合性 |
|---|---|---|
| I | 自实现 ReAct Loop | ✅ CLI 只转交 `AgentService.process`，不触碰循环 |
| II | Spring AI 只做协议转换 | ✅ 装配配置沿用 `SpringAiProviderServiceImpl`（proxyToolCalls=TRUE 已内置）；autoconfigure exclude 沿用 boot 配置 |
| III | Provider 显式映射 | ✅ 装配配置里 `ProviderChatModelFactory.build(properties)` → 显式 providerMap，不扫 Bean 类型 |
| IV | SKILL.md 归 ContextLoader | ✅ 装配注入 ContextLoader(.oryxos 根)，CLI 不碰 Skill |
| V | 审计 Day One 落库 | ✅ 装配接入两个 Jpa auditor；重命令启动即校验仓库就位（FR-008） |
| VI | 沙箱白名单 / 凭证环境变量 | ✅ 本节无新涉外 IO；Profile 凭证 `${ENV}` 占位由 16 节 ProfileLoader 解析 |
| VII | 同步 + 虚拟线程 | ✅ chat 循环同步阻塞读 stdin；无异步原语 |
| VIII | 手工建表 / 状态外置 | ✅ sessions 手工 DDL；**纠正 boot 遗留 `ddl-auto: create-drop` → `none` + `sql.init.mode=always`**（不纠正则重启毁数据，直接违宪） |

**Post-design 复查**：修改点均为课件明文交付或 17 节预告的改造点（SessionManager 补全、Session 恢复构造器）；无新增软门禁项。宪法 v1.1.0 模块条款：跨模块契约（SessionManager）在 core、实现在 storage，方向正确。

## Project Structure

### Documentation (this feature)

```text
specs/003-cli-entry/
├── plan.md              # 本文件
├── research.md          # D1~D8 决策
├── data-model.md        # sessions 表 / 实体 / 领域对象改造
├── quickstart.md        # 验证指引
├── contracts/cli.md     # 命令面 + 会话管理契约
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
oryxos-core/
└── src/main/java/io/oryxos/core/session/
    ├── SessionManager.java          # 【补全】+getOrCreate(channel,userId,profileName) +get(sessionId)（17 节前向仅 save）
    └── Session.java                 # 【改造点】+恢复构造器 Session(sessionId, profileName, List<Message>)（17 节 D2 预告）

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── Session.java                 # 【交付物】JPA 实体 @Table("sessions")（与 core 领域对象同名不同包）
│   ├── SessionRepository.java       # 【交付物】JpaRepository<Session,String>
│   └── JpaSessionManager.java       # SessionManager 实现：id 拼接唯一发生地（channel:user:profile）、
│                                    #   getOrCreate 恢复/新建、save 序列化+刷 last_active_at
├── src/main/resources/schema.sql    # 追加 sessions DDL
└── src/test/java/io/oryxos/storage/
    ├── SessionManagerTest.java      # 【harness】幂等/隔离/id 单点
    └── SessionRepositoryTest.java   # 【harness】存读/messages_json 回读/重启恢复

oryxos-channel-cli/
├── pom.xml                          # 依赖 oryxos-core（已有）
└── src/main/java/io/oryxos/channel/cli/
    └── CliChannel.java              # 【交付物】chat 交互循环：读 stdin→AgentService.process→stdout，/quit 退出

oryxos-cli/
├── pom.xml                          # +channel-cli/provider/storage/spring-boot-starter/starter-data-jpa/sqlite-jdbc
└── src/main/java/io/oryxos/cli/
    ├── OryxOsCli.java               # 【交付物】main + @Command 根（subcommands=12 个）
    ├── OryxOsRuntime.java           # 重命令 Spring 装配：@SpringBootApplication
    │                                #   + @EnableJpaRepositories/@EntityScan(basePackages="io.oryxos.storage")（坑四）
    │                                #   + @Bean 显式装配全链（providerMap→SpringAiProviderServiceImpl→…→AgentService）
    └── command/
        ├── InitCommand.java  StatusCommand.java  ChatCommand.java  ServeCommand.java  GatewayCommand.java
        ├── ProfileCommand.java      # 父命令，嵌套子命令 list/create/show/delete
        ├── ProviderListCommand.java  ToolListCommand.java  SessionListCommand.java

oryxos-boot/
└── src/main/resources/application.yml  # 【纠正】ddl-auto: create-drop → none；+spring.sql.init.mode=always
```

**Structure Decision**: 命令类集中 `io.oryxos.cli.command` 包；profile 的 4 个动作作为 `ProfileCommand` 的嵌套子命令类（Picocli `subcommands` 属性）。计 12 个命令：init/status/chat/serve/gateway/profile(list/create/show/delete)/provider list/tool list/session list。

## Complexity Tracking

> 本节无宪法违背项。两处前序类型改动均为预告改造点（17 节 research D2 明文"18 节交付 JPA 实体/Repository/getOrCreate 完整签名与实现"；SessionManager 补全是 18 节课件交付物本体），在 tasks 停点向用户展示但不构成需特批的软门禁。
