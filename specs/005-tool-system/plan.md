# Implementation Plan: Tool 体系——Agent 真正能动手干事的那双手

**Branch**: `class-20`（用户指定） | **Date**: 2026-07-11 | **Spec**: [spec.md](./spec.md)

## Summary

oryxos-tool 交付 Tool 体系全量：`ToolRegistry`（直接注册 + `@Tool` 注解扫描两条路径，重名拒绝，按 Profile.tools 过滤）；六个内置工具（`FileTools`×3 / `ShellTools` / `HttpTools`×2，全部方法第一行 `sandbox.enforce`）；MCP 接入（`McpClientService` 读 `.oryxos/mcp_servers.yaml` 连接注册、`McpToolAdapter` 协议转发，失联 WARN 跳过）；Sandbox **接口**前向定义（实现归 24 节）；19 节 notify 直接注册；OryxOsRuntime 用注册表替换空工具 Map，chat 链路接通。

## Technical Context

**Language/Version**: Java 21，同步阻塞（宪法 VII）

**Primary Dependencies**（javap 已实核）: spring-ai-core M6 `@Tool`/`@ToolParam` + `ToolCallbacks.from(Object...)` → `ToolCallback[]`（`getToolDefinition()`→`ToolDefinition.name()/description()/inputSchema()`、`call(String)→String`）——宪法 II 第二件事（schema 生成）的正牌管道；spring-ai-mcp 1.0.0-M6（BOM 管理，已拉取）→ 传递 MCP java-sdk 0.9.0：`McpClient.sync(McpClientTransport)` builder → `McpSyncClient.listTools()/callTool(CallToolRequest)/closeGracefully()`；SnakeYAML（mcp_servers.yaml）；RestClient（HttpTools，tool 模块已有 spring-web）

**Storage**: 无新表；工具执行审计走 17 节 ToolExecutor 既有 tool_invocations 路径

**Testing**: 7 个测试类照课件 harness；MCP 全 mock（Mockito 5 mock `McpSyncClient`）；HttpTools 用 JDK HttpServer 假服务（19 节同款）；拦截用例注入"拒绝一切"的 Sandbox 替身（clarify 2）

**Constraints**: 内置工具构造强制要求 Sandbox 参数（不许裸奔）；`@Tool` 方法异常直接传播（ToolExecutor 统一转失败结果+审计——17 节既有语义）；测试方法名英文 + @DisplayName 课件中文原名；避开 Java 18+ 语法形态；日志字符形态消毒

**Scale/Scope**: tool 模块 ~10 个主类型 + 7 个测试类；core 零改动；OryxOsRuntime 接线改造（17/19 节 plan 预告项）

## Constitution Check

| # | 原则 | 符合性 |
|---|---|---|
| I | 自实现 ReAct | ✅ 不触碰循环；工具经 17 节 ToolExecutor 执行 |
| II | Spring AI 只做两件事 | ✅ 本节正是第二件事（@Tool schema 生成）的落地；`ToolCallbacks.from` 只用于 schema+方法反射调用包装，无 ChatClient、无自动执行路径（执行由 ToolExecutor 发起） |
| III | Provider 显式映射 | ✅ 不涉及 |
| IV | Tool 模块三合一 / SKILL.md 归 ContextLoader | ✅ Registry+内置+MCP 全在 oryxos-tool 一个模块；SKILL.md 不碰 |
| V | 审计 Day One | ✅ 全部工具经 ToolExecutor 统一审计，零新增审计逻辑 |
| VI | 沙箱白名单 | ✅ Sandbox 接口本节接入执行链第一步（课件明文）；实现归 24 节；临时 PermissiveSandbox 每次放行记 WARN（停点确认项） |
| VII | 同步 + 虚拟线程 | ✅ McpSyncClient 同步档；Shell 用 ProcessBuilder.waitFor 超时 |
| VIII | 手工建表 | ✅ 无新表 |

## Project Structure

```text
oryxos-tool/
├── pom.xml                          # +spring-ai-core、+spring-ai-mcp（BOM 版本）、+snakeyaml（停点确认项）
└── src/
    ├── main/java/io/oryxos/tool/
    │   ├── ToolRegistry.java            # 【交付物】register(OryxTool)重名拒绝 / registerAnnotated(Object)
    │   │                                #   / contains / get / all / asMap / filterByNames(List)
    │   ├── AnnotatedToolAdapter.java    # @Tool 管道：ToolCallback → OryxTool（注解扫描注册的机制本体）
    │   ├── sandbox/                     # 【前向定义，实现归 24 节——课件"先以接口调用形式接入"】
    │   │   ├── Sandbox.java             #   void enforce(SandboxAction)
    │   │   ├── SandboxAction.java       #   record(ActionType type, String target)（课件字面量）
    │   │   ├── ActionType.java          #   FILE_READ / FILE_WRITE / SHELL_COMMAND / HTTP_REQUEST
    │   │   ├── SandboxViolationException.java
    │   │   └── PermissiveSandbox.java   #   24 节前的临时装配：放行但每次 WARN（停点确认项）
    │   ├── builtin/
    │   │   ├── FileTools.java           # 【交付物】@Tool read_file/write_file/list_dir，首行 enforce
    │   │   ├── ShellTools.java          # 【交付物】@Tool shell，命令检查位+30s 超时
    │   │   ├── HttpTools.java           # 【交付物】@Tool http_get/http_post，域名检查位
    │   │   └── NotifyTools.java         # （19 节既有）本节 registry.register() 完成注册
    │   └── mcp/
    │       ├── McpServerConfig.java     # record(name/transport/command/env)
    │       ├── McpConfigLoader.java     # 读 .oryxos/mcp_servers.yaml（servers: 列表；缺文件=零 server）
    │       ├── McpClientService.java    # 【交付物】connectAll：连接→listTools→包装注册；失联 WARN 跳过
    │       └── McpToolAdapter.java      # 【交付物】McpSchema.Tool→OryxTool；callTool 转发；失败可重试
    └── test/java/io/oryxos/tool/
        ├── OryxToolContractTest.java    # 参数化遍历 registry 契约三件套（中文名关键回归）
        ├── ToolRegistryTest.java        # 三来源注册 / 重名拒绝 / 过滤不多不少
        ├── builtin/FileToolsTest.java  ShellToolsTest.java  HttpToolsTest.java   # 正常+拦截
        └── mcp/McpClientServiceTest.java  McpToolAdapterTest.java                # 失联隔离（中文名关键回归）/ 转发包装

oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java   # 【接线改造，17/19 节 plan 预告】
    # +Sandbox(Permissive) +RestClient +ToolRegistry bean（注册内置+notify+MCP connectAll）
    # tools Map bean 改 registry.asMap()
```

**关键设计（research 决策合并于此，全部 javap 实核后定）**：

- **D1 注解管道**：`AnnotatedToolAdapter implements OryxTool` 包装 `ToolCallback`——name/description/inputSchema 取自 `getToolDefinition()`，`execute(JsonNode)` → `callback.call(json.toString())` → `ToolResult.ok(返回串)`；异常不 catch（传播给 ToolExecutor 统一转失败+审计）。`registerAnnotated(bean)` 内部 `ToolCallbacks.from(bean)` 逐个包装注册。
- **D2 Sandbox 前向**：四个类型字面量全部来自课件 19/20 节示意代码（`Sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, url))`）与 TechSol §6.7 预告；`PermissiveSandbox` 放行但 WARN"白名单未启用（24 节接入）"——比空实现留痕、比不装工具能跑 Demo。
- **D3 内置工具**：方法体第一行 enforce（FILE_READ/FILE_WRITE 按读写、SHELL_COMMAND 传完整命令、HTTP_REQUEST 传 URL）；shell 用 `ProcessBuilder("bash","-c",cmd)` + `waitFor(30s)` 超时 destroyForcibly、非零退出码抛异常带 stderr；文件不存在等抛 IllegalArgumentException 点名——真实链路由 ToolExecutor 统一转失败结果（spec edge case 语义在链路层达成）。
- **D4 MCP**：`McpConfigLoader` 解析 `servers:` 列表（command 字符串空格拆分为可执行+args；env 值支持 `${ENV}` 占位）；`McpClientService.connectAll(registry)` 对每个 config：Stdio transport → `McpClient.sync(...).build()` → initialize → listTools → 逐个 `McpToolAdapter` 注册，任何异常 WARN 跳过该 server；构造注入 `Function<McpServerConfig, McpSyncClient>` 连接工厂（测试替换 mock 工厂）。`McpToolAdapter`：inputSchema 为 McpSchema.Tool 的 schema JSON 序列化；`callTool(new CallToolRequest(name, argsMap))`，isError → `ToolResult.error(内容, true)`（可重试），成功 → 文本 content 拼接 `ToolResult.ok`。
- **D5 装配**：OryxOsRuntime 新增 Sandbox/RestClient/ToolRegistry bean；注册顺序：内置（注解管道）→ notify（直接）→ MCP（connectAll）；`tools()` bean 改返回 `registry.asMap()`——PromptBuilder 按 Profile.tools 过滤的既有逻辑随之生效。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Sandbox 前向包 + PermissiveSandbox（清单外类型，停点确认） | 课件明文"白名单校验先以接口调用形式接入"且 harness 有拦截用例；Runtime 装配内置工具必须有 Sandbox 实例 | 不装内置工具（Demo 一跑不了、tool list 人工项作废）；空实现不留痕（Permissive 每次 WARN） |
| 新增依赖 spring-ai-core/spring-ai-mcp/snakeyaml 到 oryxos-tool（软门禁 6，停点确认） | @Tool schema 生成（宪法 II 正牌用途）与 MCP 官方管道，均在项目 BOM | 手写 JSON-RPC/stdio 客户端（重复造轮子且课件明示用 MCP 生态） |
| mcp_servers.yaml 顶层 `servers:` 键、command 空格拆分（格式推定默认，停点确认） | 课件只给字段名未给文件结构 | — |
