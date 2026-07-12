# Implementation Plan: Sandbox 白名单——把工具执行前那道安全校验的墙真正砌起来

**Branch**: `class-24` | **Date**: 2026-07-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-sandbox-whitelist/spec.md`

## Summary

把第20节交付的 `Sandbox` 抽象从"放行一切只记告警"的 `PermissiveSandbox` 换成真正的白名单实现 `WhitelistSandbox`：按 `ActionType` 路由到文件路径 / 命令首 token / HTTP 域名三类校验，任一不过抛既有 `SandboxViolationException`、动作零发生。三块白名单来自配置（`file.allowed_paths` / `shell.allowed_commands` / `http.allowed_domains`），空列表天然 deny-all。补齐第19节预告的 `NotifyTools` 改造点（构造加 `Sandbox`，推送前 `enforce(HTTP_REQUEST)`）。运行时装配替换 sandbox bean 并注入三配置类。校验失败复用第17节 `ToolExecutor` 失败审计路径，不新增审计逻辑。实现细节逐字对齐第24节课件 §3.2。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.x（`@ConfigurationProperties`）、JDK 标准库 `java.nio.file.Path` / `java.net.URI`。**本节不新增第三方依赖**——白名单校验纯 JDK。

**Storage**: 无表变更；审计复用既有 `tool_invocations`（第17节链路）。

**Testing**: JUnit 5 + Mockito（`verify(..., never())` 断言底层 IO 零触达）；`@TempDir` 造真实路径给 `checkFilePath`。

**Target Platform**: 企业内 K8s / 服务器（同项目基线）。

**Project Type**: 单体多模块 Maven（library/cli），落 `oryxos-tool` + `oryxos-cli` 装配 + `oryxos-boot` 配置。

**Performance Goals**: 校验是同步、进程内、O(白名单长度) 比对，微秒级，无性能目标。

**Constraints**: 同步阻塞（宪法 VII，无 Reactor/CompletableFuture）；避开 P3C/ASM 不解析的 Java 18+ 语法（增强 switch `default ->` 等），沿用第20节验证过的 record + 传统 switch 形态。

**Scale/Scope**: 4 个受管工具（文件/命令/HTTP/通知）；新增 1 实现类 + 3 配置 record；改 2 处（NotifyTools 构造、OryxOsRuntime 装配）+ 1 配置文件 + 2 处反向文档同步。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 本节相关性 | 结论 |
|---|---|---|
| VI 安全是地基：强制沙箱白名单，不用 SecurityManager (NON-NEGOTIABLE) | **本特性即此原则的兑现** | ✅ 文件走路径白名单、Shell 走命令首 token、HTTP 走域名通配白名单，三者齐；不用 SecurityManager；凭证不涉及（纯路径/命令/域名字符串，走 application.yaml） |
| V 审计 Day One 落库 (NON-NEGOTIABLE) | 校验失败要留痕 | ✅ 复用第17节 `ToolExecutor` 失败审计路径（抛异常→既有 catch 落 `tool_invocations` success=false），不新增审计逻辑 |
| VII 同步执行 + 虚拟线程 | 校验为同步 | ✅ 纯同步比对，无异步模型 |
| IV/VIII 模块单一、`oryxos-tool` 不拆 | sandbox 落哪 | ✅ sandbox 包全落 `oryxos-tool`，不新建模块（无「模块演进」条款触发） |
| I/II/III | 不涉及 ReAct/Provider | N/A |

**Gate: PASS**（无违规，无需 Complexity Tracking）。改造点声明：`NotifyTools` 构造签名变更（第19节课件明文预告的接线位）+ `OryxOsRuntime` sandbox bean 替换——均为课件列明的改造点，非「改前序节公共接口」软门禁项。

### 反向文档同步（本节交付物含文档一致性）

第20节已交付 `ActionType` 为四值（`FILE_READ`/`FILE_WRITE`/`SHELL_COMMAND`/`HTTP_REQUEST`），而 `docs/TechnicalSolution.md §6.7` 与第24节课件 §3.1 示例仍写三值（`FILE_ACCESS`/`SHELL_EXEC`/`HTTP_REQUEST`）。停点已确认：**保留代码四值、反向同步文档**。`WhitelistSandbox.enforce` 的 switch 把 `FILE_READ` 与 `FILE_WRITE` 两 case 都路由到 `checkFilePath`。

## Project Structure

### Documentation (this feature)

```text
specs/007-sandbox-whitelist/
├── plan.md              # 本文件
├── research.md          # Phase 0：三处实现决策 + 反向文档同步决策
├── data-model.md        # Phase 1：WhitelistSandbox + 三配置 record 结构
├── quickstart.md        # Phase 1：真实链路越界拦截 + harness 验证入口
├── contracts/
│   └── sandbox.md       # Phase 1：enforce 契约（既有，本节只补白名单语义）
└── tasks.md             # Phase 2（/speckit-tasks 生成，非本命令）
```

### Source Code (repository root)

```text
oryxos-tool/src/main/java/io/oryxos/tool/sandbox/
├── Sandbox.java                    # 【不改】第20节接口
├── SandboxAction.java              # 【不改】第20节值对象
├── ActionType.java                 # 【不改】第20节四值枚举
├── SandboxViolationException.java  # 【不改】第20节异常
├── PermissiveSandbox.java          # 【保留】临时实现（装配处不再引用，类留档）
├── WhitelistSandbox.java           # 【新增】真正的白名单实现
├── FileSandboxProperties.java      # 【新增】@ConfigurationProperties(prefix="file")
├── ShellSandboxProperties.java     # 【新增】@ConfigurationProperties(prefix="shell")
└── HttpSandboxProperties.java      # 【新增】@ConfigurationProperties(prefix="http")

oryxos-tool/src/main/java/io/oryxos/tool/builtin/
└── NotifyTools.java                # 【改】构造加 Sandbox；send 前 enforce(HTTP_REQUEST)

oryxos-cli/src/main/java/io/oryxos/cli/
└── OryxOsRuntime.java              # 【改】sandbox() → WhitelistSandbox；@EnableConfigurationProperties 加三配置；NotifyTools 传 sandbox

oryxos-boot/src/main/resources/
└── application.yml                 # 【改】加 file/shell/http 三块白名单

oryxos-tool/src/test/java/io/oryxos/tool/sandbox/
└── WhitelistSandboxTest.java       # 【新增】三类放行/拒绝 + 两关键回归

oryxos-tool/src/test/java/io/oryxos/tool/builtin/
└── （FileTools/ShellTools/HttpTools/NotifyToolsTest 各补一条"白名单外被拦、底层 verify never"）

docs/
├── TechnicalSolution.md            # 【改】§6.7 ActionType 反向同步四值
└── class/第24节：Sandbox 实现与代码讲解.md  # 【改】§3.1 ActionType 示例反向同步四值 + switch 四 case
```

**Structure Decision**: 单体多模块，sandbox 全落 `oryxos-tool`（宪法 IV：不拆 tool 模块）；装配落 `oryxos-cli`；配置落 `oryxos-boot`。无新建模块，不触发「模块演进」条款。

## Complexity Tracking

> 无宪法违规，本节留空。
