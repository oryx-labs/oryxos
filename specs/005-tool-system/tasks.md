# Tasks: Tool 体系——Agent 真正能动手干事的那双手

**Input**: specs/005-tool-system/（plan.md 含 D1~D5 / data-model.md / contracts/tool-system.md / quickstart.md）

**Tests**: 课件 harness 7 个测试类全落本节；两个中文名关键回归原样落地（英文方法名 + @DisplayName）。

**Organization**: Sandbox 前向 + Registry 地基（US1）→ 内置工具（US2）→ 注解管道验证（US4，与 US2 同管道一并验证）→ MCP（US3）→ 装配接线 + Polish。

## Phase 1: Setup

- [x] T001 基线：`mvn test -q` 确认 16~19 节 81 测试全绿
- [x] T002 oryxos-tool pom：+spring-ai-core、+spring-ai-mcp（均 BOM 版本）、+snakeyaml；`mvn dependency:tree -pl oryxos-tool | grep -E "spring-ai|modelcontextprotocol"` 确认解析（含 MCP sdk 传递版本记录）

## Phase 2: US1 地基——Sandbox 前向 + ToolRegistry（P1，harness 先行）

- [x] T003 [P] [US1] sandbox 前向四件 + Permissive：`oryxos-tool/src/main/java/io/oryxos/tool/sandbox/`——Sandbox 接口（enforce(SandboxAction)）、SandboxAction record(ActionType, String target)、ActionType enum（FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST）、SandboxViolationException、PermissiveSandbox（放行+每次 WARN"白名单未启用，24 节接入"）
- [x] T004 [US1] ToolRegistryTest：`oryxos-tool/src/test/java/io/oryxos/tool/ToolRegistryTest.java`——①三种来源（直接 register 的 OryxTool 替身、registerAnnotated 的 @Tool bean、MCP adapter 实例）都以 OryxTool 身份可查；②重名 register 抛 IllegalStateException 点名；③`@DisplayName("按Profile的tools字段过滤_子集恰好等于声明列表")`：filterByNames 结果不多不少、未知名跳过
- [x] T005 [US1] ToolRegistry + AnnotatedToolAdapter：`oryxos-tool/src/main/java/io/oryxos/tool/`——按 contracts §1；AnnotatedToolAdapter 包装 ToolCallback（D1：name/description/inputSchema 取 getToolDefinition()，execute→call(json.toString())→ToolResult.ok，异常传播）
- [x] T006 [US1] 阶段门禁：`mvn test -pl oryxos-tool -am -Dtest='ToolRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿

## Phase 3: US2+US4 内置工具与注解管道（P1）

- [x] T007 [US2] FileToolsTest：`.../builtin/FileToolsTest.java`——read_file 读到 @TempDir 文件内容/write_file 写入/list_dir 列出（正常）；注入拒绝替身（mock Sandbox enforce 抛 SandboxViolationException）后三个动作零发生（`@DisplayName("越界会被拦")` 系列）；读不存在文件异常点名路径
- [x] T008 [US2] FileTools：`.../builtin/FileTools.java`——@Tool(name="read_file"/"write_file"/"list_dir") 三方法 + @ToolParam；每方法第一行 sandbox.enforce（FILE_READ/FILE_WRITE/FILE_READ）；Files API 实现
- [x] T009 [P] [US2] ShellToolsTest + ShellTools：`.../builtin/`——echo 正常拿 stdout；非零退出码异常带 stderr；超时（测试用短超时构造重载）destroyForcibly 报超时；拒绝替身拦下命令未跑；实现 @Tool(name="shell")，ProcessBuilder("bash","-c",cmd)，默认 30s 超时常量
- [x] T010 [P] [US2] HttpToolsTest + HttpTools：`.../builtin/`——JDK HttpServer 假服务：http_get 取回响应体（课件用例 `@DisplayName("http_get_应能取回响应")`）、http_post 提交 body；`@DisplayName("http_get_命中白名单外域名应被拦下")` 拒绝替身 assertThrows 且零请求；实现 @Tool(name="http_get"/"http_post")，RestClient，首行 enforce(HTTP_REQUEST, url)
- [x] T011 [US2] 阶段门禁：`mvn test -pl oryxos-tool -am -Dtest='FileToolsTest,ShellToolsTest,HttpToolsTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿
- [x] T012 [US4] OryxToolContractTest：`.../OryxToolContractTest.java`——**关键回归** `everyRegisteredToolHasFullContract` `@DisplayName("每个工具的契约三件套都不能缺")`：@MethodSource 构造 registry（注册 File/Shell/Http 注解管道 + notify 直接注册）参数化遍历断言 getName/getDescription/getInputSchema 全非空（断言逻辑照课件逐条保真）；另断言 File/Http 工具 schema 含参数名（注解管道 schema 生成有效性，US4）

## Phase 4: US3 MCP 接入（P2）

- [x] T013 [US3] McpServerConfig + McpConfigLoader：`.../mcp/`——record 四字段；loader 读 `.oryxos/mcp_servers.yaml` 顶层 servers: 列表（构造注入 Path，@TempDir 可测），command 空格拆分、env 值 ${ENV} 占位解析（缺失保留原样 WARN，16 节口径）、文件缺失返回空列表
- [x] T014 [US3] McpToolAdapterTest + McpToolAdapter：`.../mcp/`——mock McpSyncClient：name/description/inputSchema 从 McpSchema.Tool 映射（schema 序列化为 JSON 串）；execute 断言 CallToolRequest(name, args) 原样转发；成功文本包 ToolResult.ok；isError → `ToolResult.error(内容, retryable=true)`（`@DisplayName("MCP调用失败_包装为可重试的失败结果")`）
- [x] T015 [US3] McpClientServiceTest + McpClientService：`.../mcp/`——mock 连接工厂（Function<Config, McpSyncClient>）：**关键回归** `oneMcpServerDown_doesNotBreakStartupOrOtherTools` `@DisplayName("某个MCP_server失联_不能拖垮启动和其他工具")`：坏工厂抛 ConnectException 时 connectAll 不抛、好 server 的工具 contains=true、坏的 false（断言照课件逐条保真）；listTools 逐个包装注册；transport 非 stdio WARN 跳过
- [x] T016 [US3] 阶段门禁：`mvn test -pl oryxos-tool -am` 全绿（本节 7 类 + 19 节回归）

## Phase 5: 装配接线 + Polish

- [x] T017 OryxOsRuntime 接线（17/19 节 plan 预告改造）：`oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java`——+Sandbox bean（PermissiveSandbox）、+RestClient bean、+McpConfigLoader/McpClientService bean、+ToolRegistry bean（registerAnnotated(FileTools/ShellTools/HttpTools) + register(NotifyTools 经 Map adapters 构造) + mcp.connectAll）；`tools()` 改 `registry.asMap()`；NotifyTools 的 adapters Map 装配（webhook/wecom/feishu/dingtalk 四实现）
- [x] T018 全仓硬门禁：`mvn clean verify` 全绿（含静态检查），红了修实现
- [x] T019 H4 六条自查 + 交付物 ls/grep 核对（含：内置工具首行 enforce grep、core 零改动确认）
- [x] T020 验收报告：`specs/005-tool-system/acceptance-report.md`（六项证据 + 人工项清单）

## Dependencies

- T002 → 全部；T003 → T005/T007+；T004 先于 T005（harness 先行）；T007 先于 T008；T012 依赖 T005/T008~T010 + 19 节 NotifyTools；T013 → T014/T015；T017 依赖全部主代码。
- 并行：T003∥T004；T009∥T010。

## Implementation Strategy

- MVP = Phase 2+3（地基+内置工具，Demo 一动作就位）；Phase 4 补 MCP；T017 一次接线。
- 每阶段门禁当场修红。
