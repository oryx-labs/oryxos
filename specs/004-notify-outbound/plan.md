# Implementation Plan: Notify——结果主动送出去的统一出口

**Branch**: `class-19`（用户指定） | **Date**: 2026-07-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-notify-outbound/spec.md`

## Summary

oryxos-tool 首开：`io.oryxos.tool.notify` 包交付出站通知抽象（`NotifyChannelAdapter` 接口 + `NotifyTarget` record）与核心阶段唯一实现 `WebhookNotifyAdapter`（RestClient POST JSON，非成功即抛）；`io.oryxos.tool.builtin.NotifyTools` 以 **OryxTool 实现**形态交付工具骨架（渠道解析 + 沙箱检查位注释 + 委托发送），直接可被 17 节 ToolExecutor 执行、待 20 节 ToolRegistry 收编。harness 第一批本节全跑（假 webhook 用 JDK 内置 HttpServer，零新依赖）；InOrder 白名单顺序回归留 24 节（课件明文分批）。

## Technical Context

**Language/Version**: Java 21（发送同步阻塞——宪法 VII）

**Primary Dependencies**: spring-web 6.1.5（RestClient，Boot BOM 管理；API 经 javap 实核：`RestClient.create()`/`post()`/`contentType(MediaType)`/`body(Object)`/`retrieve().toBodilessEntity()`）；Jackson（RestClient JSON 转换器，classpath 已有）；测试假 webhook 用 JDK 内置 `com.sun.net.httpserver.HttpServer`（**零新第三方依赖**——课件提及 MockWebServer，改用 JDK 等能力替代，理由见 research D5，停点确认项）

**Storage**: 无新表——notify 经 17 节 ToolExecutor 统一路径写 tool_invocations（FR-004，不新增审计逻辑）

**Testing**: JUnit 5 + Mockito；`WebhookNotifyAdapterTest`（真 HTTP 到本地假 webhook）+ `NotifyToolsTest` 第一批可测集（mock adapter + ProfileContext set/clear）；InOrder 白名单断言归 24 节

**Target Platform**: JVM 21

**Project Type**: Maven 多模块（本节只动 oryxos-tool 及其 pom）

**Performance Goals**: 单次同步 POST；无额外目标

**Constraints**: 接口语汇渠道中立（FR-001）；不造 Sandbox 空壳（检查位注释，24 节接线——17 节 ToolExecutor 同款先例）；测试方法名英文 + @DisplayName 课件中文原名；避开 Java 18+ 语法形态；日志参数字符形态消毒

**Scale/Scope**: 4 个主类型（接口/record/实现/工具）+ 2 个测试类 + tool pom 两个依赖

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 | 本节符合性 |
|---|---|---|
| I | 自实现 ReAct Loop | ✅ 不触碰循环；notify 是被循环调度的工具 |
| II | Spring AI 只做协议转换 | ✅ 本节不用 Spring AI（@Tool 挂载归 20 节；NotifyTools 走 OryxTool 接口） |
| III | Provider 显式映射 | ✅ 不涉及 |
| IV | SKILL.md 归 ContextLoader / Tool 模块三合一 | ✅ notify 落 oryxos-tool（内置 Tool 归属正确），不拆模块 |
| V | 审计 Day One 落库 | ✅ 走 ToolExecutor 既有 tool_invocations 路径，成败都记（US2 场景 3 即证） |
| VI | 沙箱白名单 / 凭证环境变量 | ✅ 发送前留 `Sandbox.enforce` 调用位注释（24 节接线，与 http_post 共享白名单）；webhook 地址 `${ENV}` 占位由 16 节 ProfileLoader 解析 |
| VII | 同步 + 虚拟线程 | ✅ RestClient 同步调用；无异步原语 |
| VIII | 手工建表 | ✅ 无新表 |

**Post-design 复查**：无前序接口修改；新增 public 类型均为课件交付物点名（NotifyTools 的 OryxTool 形态适配见 research D3，停点确认）。

## Project Structure

### Documentation (this feature)

```text
specs/004-notify-outbound/
├── plan.md / research.md / data-model.md / quickstart.md
├── contracts/notify.md
└── tasks.md（/speckit-tasks 产出）
```

### Source Code (repository root)

```text
oryxos-tool/
├── pom.xml                          # +spring-web（RestClient）、+spring-boot-starter-test(test)
└── src/
    ├── main/java/io/oryxos/tool/
    │   ├── notify/
    │   │   ├── NotifyChannelAdapter.java   # 【交付物】接口：send(NotifyTarget, String)（课件字面量签名）
    │   │   ├── NotifyTarget.java           # 【交付物】record(channelType, Map<String,String> config)
    │   │   └── WebhookNotifyAdapter.java   # 【交付物】RestClient POST JSON；非成功即抛；url 取自 config
    │   └── builtin/
    │       └── NotifyTools.java            # 【交付物】implements OryxTool（name="notify"）；
    │                                       #   渠道解析（ProfileContext.current().notifyChannels()）
    │                                       #   + 沙箱检查位注释（24 节）+ adapter.send
    └── test/java/io/oryxos/tool/
        ├── notify/WebhookNotifyAdapterTest.java   # 【harness 第一批】JDK HttpServer 假 webhook
        └── builtin/NotifyToolsTest.java           # 【harness 第一批可测集】mock adapter；InOrder 留 24 节
```

**Structure Decision**: 包名照课件字面量（io.oryxos.tool.notify / io.oryxos.tool.builtin）；oryxos-tool 保持纯 POJO（不加 @Component——16/17/18 节装配模式统一走 OryxOsRuntime @Bean，20 节 ToolRegistry 接线时统一装配）。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| NotifyTools 以 OryxTool 接口形态落地（课件示意代码为 @Tool 注解方法）——软门禁项，停点确认 | 课件"实现顺序说明"明文 @Tool 注册机制归 20 节；17 节 ToolExecutor 消费的是 `Map<String,OryxTool>`——OryxTool 形态本节即可被执行与审计，@Tool 形态要等 20 节才有消费方 | 照抄 @Tool 注解：本节无法接线（无注册机制）、还得引入 Spring AI 依赖到 oryxos-tool，违"只创建可用的对外概念"精神；20 节若确立 @Tool 桥接机制再适配 |
| 假 webhook 用 JDK HttpServer 而非课件提及的 MockWebServer——停点确认 | 零新第三方依赖（MockWebServer 需向根 pom 加 okhttp 系两个 GAV，触软门禁 6）；JDK HttpServer 同样起真 HTTP 端口、断言请求体，满足课件"本地假 webhook、仍是单测层"的全部意图 | 引 MockWebServer：多两个依赖 + OWASP 扫描面扩大，收益仅是 API 顺手 |
