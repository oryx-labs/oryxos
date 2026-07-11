# 第20节验收报告：Tool 体系——Agent 真正能动手干事的那双手

**日期**: 2026-07-11 | **分支**: `class-20` | **任务**: 20/20 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecBugs 全部门禁。`BUILD SUCCESS`，测试 117 个全过：

```text
oryxos-core 34 | oryxos-provider 14 | oryxos-storage 15
oryxos-tool 54（本节新增 36：Contract 14 / Registry 3 / File 5 / Shell 4 / Http 3 / McpAdapter 3 / McpClient 4 + 19 节 18）
```

过程中被门禁拦下并修复：假服务缺 Content-Type 中文乱码 ×1、getParent 判空局部变量化 ×1、stale-core 教训重现（-pl 不带 -am）×1。

### 2. harness 测试类逐一对号 ✅

七个测试类全部存在非空；两个中文名关键回归**原样落地**：
`everyRegisteredToolHasFullContract` `@DisplayName("每个工具的契约三件套都不能缺")`（参数化遍历 registry，name/description/inputSchema 全非空——新工具自动纳入契约检查）；
`oneMcpServerDown_doesNotBreakStartupOrOtherTools` `@DisplayName("某个MCP_server失联_不能拖垮启动和其他工具")`（connectAll 不抛 + good_mcp_tool 在 / bad 不在，断言照课件逐条保真）。
另：课件正文 http_get 两用例模板照落（`http_get_应能取回响应` / `http_get_命中白名单外域名应被拦下`）；过滤"不多不少"（`按Profile的tools字段过滤_子集恰好等于声明列表`）。

### 3. 交付物存在性核对 ✅

- 代码 10 类全在（Registry/AnnotatedToolAdapter/sandbox×5/builtin×3/mcp×4）；OryxTool（含 getInputSchema）/ToolResult 为 16/17 节既有，本节 core **零改动**
- `NotifyTools` 完成注册（OryxOsRuntime registry.register，含四渠道 adapters Map 装配）
- 配置：mcp_servers.yaml 格式落地（servers: 列表，command 空格拆分，env `${ENV}` 占位）；Profile tools 过滤经既有 PromptBuilder 生效
- 内置工具首行 enforce grep：File×3（read/write/list 各一 + 计数 4 含 import）/Shell×1/Http×2 ✓
- 停点确认项全部落实：Sandbox 前向 + Permissive、三个新依赖、yaml 格式、双注册路径、Runtime 接线

### 4. 前序节回归 ✅

16~19 节 81 个测试断言零改动全绿（上表 34+14+15+18）；OryxOsRuntime 接线为 17/19 节 plan 预告的改造点。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 首行过 Sandbox.enforce | 六个内置工具全部第一行 enforce（grep 证据）；MCP 转发的安全边界在 server 侧进程隔离，Registry/Profile.tools 限定可用面 |
| ② | 审计成败都落库 | 全部工具经 17 节 ToolExecutor 统一路径（tools Map 即 registry.asMap()），零新增审计逻辑 |
| ③ | 无明文 key | grep 零命中；MCP env 走 `${ENV}` 占位 |
| ④ | session_id 只在 SessionManager 拼 | 本节不触碰 |
| ⑤ | 无异步编程模型 | McpSyncClient 同步档；grep 零命中 |
| ⑥ | 无 Spring AI 自动执行 | 无 ChatClient；@Tool 只用于 schema 生成+方法包装（宪法 II 第二件事），执行发起方唯一是 ToolExecutor |

### 6. 剩余人工项（harness 判不了，等你过）

1. **方式一真跑**：`.oryxos/skills/` 写 SKILL.md + mcp_servers.yaml 连真实 MCP server（如 github-mcp），chat 里 Agent 读懂意图调用外部工具（需真模型+真 server+ContextLoader 已就位）；
2. **方式三真跑**：chat 里让 Agent 调 read_file/http_get，`tool_invocations` 表目检有记录（真链路审计）；
3. **tool list 目检**：18 节 SessionListCommand 同款占位仍在（轻命令不起 Spring 拿不到 registry），实时清单经 chat 启动日志或后续节补；
4. ⚠️ **PermissiveSandbox 提醒**：24 节前每次工具调用都有 WARN"白名单未启用"——生产不可用状态，Demo 验证专用。

## 门禁妥协点（如实列出）

- `@SuppressFBWarnings` ×3（均局部注解带 justification，非全局排除）：ShellTools COMMAND_INJECTION（shell 工具功能本质，白名单由 enforce 前置）、McpToolAdapter EI_EXPOSE_REP2（必须持有连接引用）、PermissiveSandbox CRLF_INJECTION_LOGS（已 char 形态消毒，taint 误报）。配套引入 spotbugs-annotations（provided，编译期注解）。

## 备注

- MCP sdk 实际传递版本 0.7.0（spring-ai-mcp M6 传递；此前 javap 的 0.9.0 是本地他项目残留）——已对 0.7.0 重新实核 API 后落码。
- 未 commit/push——同步时机由你决定。
