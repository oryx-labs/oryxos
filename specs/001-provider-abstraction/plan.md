# Implementation Plan: Provider——对接大模型的统一入口（第16节）

**Branch**: `class-16` | **Date**: 2026-07-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-provider-abstraction/spec.md`

## Summary

交付 OryxOS 能力一：多 Provider 显式路由的统一 LLM 调用入口。包含 Profile 的解析加载体系（Profile/ProfileLoader/ProfileRegistry，首个消费方在此故一并交付）、`ProviderService.chat(sessionId, Profile, Prompt)`（显式 name→ChatModel 映射、关闭框架自动工具执行、工具 schema 只翻译不执行）、`llm_calls` 审计的 Day-One 写入（成败都落、失败先落再抛）。deepseek/kimi 经 OpenAI 兼容端点用 `spring-ai-openai`（M6 BOM 已核实含该构件）手工构造 ChatModel，不用 starter 自动装配。

## Technical Context

**Language/Version**: Java 21（虚拟线程，全程同步阻塞）

**Primary Dependencies**: Spring Boot 3.3.x、Spring AI `1.0.0-M6`（`spring-ai-openai`，BOM 管理——已从本地 BOM pom 查证存在）、Spring AI Alibaba `1.0.0-M6.1`（保留备用 qwen，禁用其 eager 自动装配）、SnakeYAML（随 spring-boot-starter 传递）、Jackson

**Storage**: SQLite（`sqlite-jdbc` + `hibernate-community-dialects`，均已在 oryxos-storage pom）+ Spring Data JPA；建表走手工 `schema.sql`，禁用 `ddl-auto=update`

**Testing**: JUnit 5 + Mockito（单测 mock ChatModel）；`@DataJpaTest` + 手工 schema.sql（Repository 测试）；`@Tag("integration")` 冒烟（CI 跳过）

**Target Platform**: 单可执行 fat JAR，企业内网 Linux/macOS

**Project Type**: Maven 多模块单体中的三个模块（oryxos-core / oryxos-provider / oryxos-storage）

**Performance Goals**: 本节无独立性能目标（调用耗时由外部 LLM 决定）；审计写入不阻塞主链路可用性

**Constraints**: `mvn clean verify` 全绿含 Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs/PMD/OWASP；避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch `default ->`）；凭证仅环境变量

**Scale/Scope**: 核心阶段单实例；本节代码量预估 <1.5k 行（含测试）

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 关联 | 本 plan 的符合方式 |
|---|---|---|
| I 自实现 ReAct（NON-NEG） | 间接 | 本节不实现循环；不引入任何 Spring AI Agent 抽象/ChatClient |
| II Spring AI 仅协议转换+Schema（NON-NEG） | **核心** | 调用方式 `chatModel.call(new Prompt(...))`；关闭自动工具执行（见 research D2）；禁用 DashScope eager 自动装配（research D3）；手工 `new OpenAiChatModel(...)` 不走 starter 装配 |
| III Provider 显式映射 | **核心** | 启动按 `oryxos.providers` 配置逐条构造 ChatModel，显式 `Map<String, ChatModel>`；零类型扫描 |
| IV SKILL.md 归 ContextLoader | 不涉及 | 本节不触碰 Skill/Tool 模块归属 |
| V 审计 Day One 落库（NON-NEG） | **核心** | `LlmCall` 实体 + Repository + schema.sql 本节交付；成败都写、失败先写再抛 |
| VI 安全地基（NON-NEG） | 部分 | 凭证 `${ENV}` 占位、启动校验缺失即清晰报错；本节无工具执行故无沙箱调用点 |
| VII 同步+虚拟线程 | 符合 | 无 Reactor/CompletableFuture/自建线程池 |
| VIII 配置即 Agent、状态外置 | **核心** | Profile YAML 定义 Agent 模型选择；手工建表脚本；实例内存仅缓存不可变配置索引 |

**Gate 结论：PASS**（无违背项，Complexity Tracking 留空）。

## Project Structure

### Documentation (this feature)

```text
specs/001-provider-abstraction/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── provider-service.md
└── tasks.md   # /speckit-tasks 产出
```

### Source Code (repository root)

```text
oryxos-core/src/main/java/io/oryxos/core/
├── OryxTool.java                 # 既有 stub：补 getInputSchema()（research D4）
├── ToolResult.java               # 既有，不动
└── profile/
    ├── Profile.java              # 全字段记录类（嵌套 ProviderRef/ScheduleConfig/NotifyChannel/Identity/Settings）
    ├── ProfileLoader.java        # 扫 .oryxos/profiles/ + SnakeYAML + 校验，坏文件不阻断
    ├── ProfileRegistry.java      # Map<String,Profile> 内存索引
    └── ProfileValidationException.java

oryxos-provider/src/main/java/io/oryxos/provider/
├── ProviderService.java          # chat(sessionId, Profile, Prompt)（签名逐字保真）
├── ProviderNotFoundException.java
├── ProvidersProperties.java      # oryxos.providers 全局层 @ConfigurationProperties
├── ProviderChatModelFactory.java # 按配置手工构造 ChatModel（OpenAI 兼容端点）
├── ToolSchemaAdapter.java        # OryxTool → Spring AI 工具描述，只翻译
└── LlmCallAuditor.java           # 审计接口（写 llm_calls；自身失败 log&continue）
oryxos-provider/src/test/java/io/oryxos/provider/
├── ProviderServiceTest.java      # 含课件三个中文名回归测试（原样落地）
├── ToolSchemaAdapterTest.java
└── ProviderSmokeIT.java          # @Tag("integration")
oryxos-core/src/test/java/io/oryxos/core/profile/
└── ProfileLoaderTest.java

oryxos-storage/src/main/java/io/oryxos/storage/
├── LlmCall.java                  # JPA 实体
├── LlmCallRepository.java
└── JpaLlmCallAuditor.java        # LlmCallAuditor 的 JPA 实现
oryxos-storage/src/main/resources/
└── schema.sql                    # llm_calls 手工建表（含 success/error_message）
oryxos-storage/src/test/java/io/oryxos/storage/
└── LlmCallRepositoryTest.java    # 建表走 schema.sql，不让 Hibernate 自动建
```

**Structure Decision**: 按课件模块落位表——Profile 体系归 oryxos-core（下游各节共用）、ProviderService/适配器/审计接口归 oryxos-provider、实体/Repository/建表归 oryxos-storage；审计以接口（provider 模块）+ JPA 实现（storage 模块）解耦，provider 不直接依赖 storage，装配留给 boot（本节测试中直接 mock 接口，不需要完整装配）。

## Complexity Tracking

无需填写——Constitution Check 无违背项。
