# 第24节验收报告：Sandbox 白名单——把工具执行前那道安全校验的墙真正砌起来

**日期**: 2026-07-12 | **分支**: `class-24` | **任务**: 20/20 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / Checkstyle / P3C(PMD) / SpotBugs / FindSecBugs 全部门禁。`BUILD SUCCESS`，测试 181 个全过：

```text
oryxos-core 34 | oryxos-provider 14 | oryxos-storage 18 | oryxos-memory 25
oryxos-tool 90（较 22 节 +14：WhitelistSandboxTest 9 + 四工具拦截各 +1/+2）
```

过程中被门禁拦下并修复：
- **P3C SwitchStatementRule**：增强 switch 的 `default ->` 被判"缺 default"——改传统 colon+break+default（语法禁区实锤，静态检查是门禁）。
- **P3C UndefineMagicConstantRule**：`"*."` 抽成 `WILDCARD_PREFIX` 常量。
- **SpotBugs EI_EXPOSE ×6**：三配置 record 加 compact 构造 `List.copyOf`（+null 归一），沿用 `ProvidersProperties` 同款。
- **FindSecBugs IMPROPER_UNICODE ×2**：`matchesDomain` 的 `toLowerCase(Locale.ROOT)` 局部 `@SuppressFBWarnings`（域名 ASCII，locale-safe 小写化的误报）。

### 2. harness 测试类逐一对号 ✅

`WhitelistSandboxTest`（9 个，@Nested 四组）——三类校验各"放行+越界拒绝"成对，两个**关键回归**原样落地（英文方法名 + `@DisplayName` 保留课件语义）：

- `relativePathTraversalIsBlocked` `@DisplayName("相对路径穿越_爬出白名单目录_被拦")`——白名单 `@TempDir`，`../../../../../../etc/passwd` normalize 后越界被拦（normalize 回归）。
- `wildcardDomainRespectsDotBoundary` `@DisplayName("通配符域名_命中真子域_不被形似域名绕过")`——`*.example.com` 命中 `api.example.com`/`a.b.example.com`，拒 `evil-example.com` 与裸域 `example.com`（点号边界回归，`endsWith("example.com")` 经典漏洞）。
- 另加：空白名单 = deny-all（三类全空一律拒绝）、畸形 URL 无主机名拒绝。

四工具拦截回归（白名单外被拦、真正 IO 没发生）：
- `FileToolsTest.readOutsideWhitelist_fileNeverTouched`（真 WhitelistSandbox，读白名单外抛 SandboxViolationException 而非文件缺失异常，证明校验先于 IO）
- `ShellToolsTest.commandOutsideWhitelist_processNeverStarts`（白名单 `{ls}`，`rm` 被拦，进程不启）
- `HttpToolsTest.requestOutsideWhitelist_serverNeverReceives`（真 WhitelistSandbox + 假服务，越界域名 → 假服务零收报文）
- `NotifyToolsTest.pushToDomainOutsideWhitelist_adapterNeverSends`（白名单外域名 → `verify(adapter, never()).send`）+ `pushToDomainInsideWhitelist_sendsNormally`（放行回归）

各工具原有的 mock-Sandbox 拒绝用例保留（证明 enforce 先于 IO 的接线顺序），与真 WhitelistSandbox 端到端用例互补。

### 3. 交付物存在性核对 ✅

- 代码（新增）：`WhitelistSandbox` + 三配置 record `FileSandboxProperties`/`ShellSandboxProperties`/`HttpSandboxProperties`（均 oryxos-tool/sandbox）
- 代码（复用不改）：`Sandbox`/`SandboxAction`/`ActionType`（四值）/`SandboxViolationException`（第20节交付）
- 代码（改造点）：`NotifyTools` 构造 +Sandbox、send 前 `enforce(HTTP_REQUEST)`（第19节预告）；`OryxOsRuntime` sandbox bean PermissiveSandbox→WhitelistSandbox + `@EnableConfigurationProperties` 追加三配置 + NotifyTools 传 sandbox
- 配置：`application.yml` 追加 `file.allowed_paths`/`shell.allowed_commands`/`http.allowed_domains` 三块（注明空=deny-all）
- 测试：`WhitelistSandboxTest` + 四工具拦截回归
- 反向文档同步：`docs/TechnicalSolution.md §6.7` 与第24节课件 §3.1/3.2/3.3 的 `ActionType` 三值 → 四值（对齐已交付代码）
- 停点确认过的项：①保留四枚举值反向同步文档 ④NotifyTools 加 Sandbox 构造 ⑤配置用 file/shell/http 前缀

### 4. 前序节回归 ✅

16~22 节全部测试全绿（core 34 + provider 14 + storage 18 + memory 25 = 91，加 tool 90 = 181）。三工具（File/Shell/Http）第20节 enforce 调用点零改动，原有测试全过；`OryxToolContractTest` 参数化注册面同步 NotifyTools 新构造。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | File/Shell/Http/WebSearch/**Notify** 五工具 execute 首行 `sandbox.enforce`——本节把 Notify 从注释位补成真接线，四类对外 IO 全覆盖 |
| ② | 审计成败都落库 | 校验失败抛 SandboxViolationException 上抛至第17节 ToolExecutor，落 tool_invocations success=false + 违规原因；本节不新增审计逻辑 |
| ③ | 无明文 key | sandbox 包 grep 零命中（"token" 均为"命令首 token"；application.yml 仅 `${DEEPSEEK_API_KEY:}` 占位） |
| ④ | session_id 只在 SessionManager 拼 | 本节不触碰 |
| ⑤ | 无异步编程模型 | sandbox 包 grep 无 Reactor/CompletableFuture/new Thread/Executors；纯同步比对 |
| ⑥ | 无 Spring AI 自动执行 | 本节不触碰 LLM 调用路径 |

接口中立性自查：`WhitelistSandbox` 三 `check*` + `matchesDomain` 均 private，`Sandbox` 接口仍只有 `enforce(SandboxAction)` 一个方法——未被这一档实现带偏。

### 6. 剩余人工项（harness 判不了，等你过）

1. **真实链路越界留痕**：`application.yml` 配 `shell.allowed_commands: [ls]`，启动 `oryxos chat`，诱导 Agent 跑 `rm ...`，确认被拦且 `tool_invocations` 落一条 success=false 带违规原因（"命令不在白名单内: rm"）。
2. **接口中立性思维自查**：确认未来 microVM 档能干净套入 `Sandbox.enforce` 签名（接口不含"白名单/容器/镜像"字样）。
3. **配置边界写进文档**：三块白名单键与"空=deny-all"语义已在 `application.yml` 注释 + `quickstart.md` 说明——复核措辞。

## 备注

- **faithful 修正一处**：课件 §3.2 构造只对 allowedRoots 做 `normalize()`，但 `checkFilePath` 对 target 做 `toAbsolutePath()`——相对配置（如 `.oryxos`）会永远匹配不上绝对化后的 target。本实现把根也 `toAbsolutePath().normalize()`，保持读写路径对称、让相对配置真正可用；不影响任何绝对路径用例的断言（测试用 @TempDir 绝对路径）。
- **ActionType 四值 vs 课件三值**：第20节预交付了 sandbox 前置包并按四值（FILE_READ/FILE_WRITE/SHELL_COMMAND/HTTP_REQUEST）接线三工具；本节据停点确认保留四值、反向同步文档，`enforce` switch 把 FILE_READ/FILE_WRITE 同路由 checkFilePath。
- **P3C 语法禁区实锤**：`default ->` 增强 switch 被 SwitchStatementRule 判"缺 default"——本节沿用传统 colon switch，与 skill 语法禁区一致（OryxOsRuntime 的 `default ->` 是 switch **表达式**、走另一条 P3C 路径，两者不冲突）。
- 未 commit/push——同步时机由你决定。
