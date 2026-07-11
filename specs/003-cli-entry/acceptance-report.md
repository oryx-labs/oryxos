# 第18节验收报告：CLI——OryxOS 的命令行入口

**日期**: 2026-07-11 | **分支**: `class-18` | **任务**: 26/26 完成（tasks.md 全勾）

## 六项证据 DoD

### 1. `mvn clean verify` 全绿 ✅

含 Spotless / P3C(PMD) / Checkstyle / SpotBugs / FindSecBugs 全部门禁。`BUILD SUCCESS`，测试 63 个全过：

```text
oryxos-core     34（ProfileLoader 6 / ReActLoop 8 / AgentService 5 / ToolExecutor 5 / PromptBuilder 6 / ContextLoader 4）
oryxos-provider 14（ProviderService 7 / ProvidersProperties 4 / ToolSchemaAdapter 3）
oryxos-storage  15（SessionManager 4 / SessionRepository 5 / LlmCall 3 / ToolInvocation 3）
```

过程中被门禁拦下并修复：PMD 魔法值 ×1（DB_FILE 常量化）、SpotBugs ×3（文本块 .formatted→.replace、catch(Exception) 收窄、增强 for 死变量改迭代器）——安全规则未做任何排除。

### 2. harness 测试类逐一对号 ✅

课件映射表两个测试类均存在且非空；关键回归**原样落地**：
`sameTriple_everyGetOrCreateReturnsSameSession` `@DisplayName("同一三元组_历次getOrCreate都是同一个Session")`——两次 getOrCreate 断言 id 相等（幂等）+ 换 channel 断言不等（隔离），断言逻辑照课件逐条保真。另有隔离全排列、id 格式 `cli:wang:default`、messages_json 三类角色顺序回读、模拟重启历史还在、零消息会话、last_active_at 刷新。

### 3. 交付物存在性核对 ✅

- 代码：`OryxOsCli`（main + 根命令）+ 9 个命令类文件（含 profile 嵌套 4 子命令与 provider/tool/session 的 list，计 12 个命令）+ `OryxOsRuntime`（重命令装配）→ oryxos-cli；`CliChannel` → oryxos-channel-cli；`Session` 实体 + `SessionRepository` + `JpaSessionManager` → oryxos-storage；`SessionManager` 接口补全三方法 + core `Session` 恢复构造器 → oryxos-core
- 表：`sessions` 手工 `schema.sql`（9 列 + idx_sessions_profile）
- 约定落地：轻命令零 Spring（init/status/profile×4/provider/tool/session list 全部纯文件/JDBC）；重命令显式 `@EnableJpaRepositories`/`@EntityScan`（课件坑四）
- 停点确认过的项：OryxOsRuntime 装配类、core 前序改动（17 节预告改造点）、boot 两处纠正、id 格式 `channel:user:profile`

### 4. 前序节回归 ✅

16/17 节全部测试断言零改动、全绿（上表 34+14 即证据）；D1 契约、ReAct 链、审计链未被本节触碰（core 仅加接口方法与构造器，二进制兼容）。

### 5. H4 六条全局不变量自查 ✅

| # | 不变量 | 结论 |
|---|---|---|
| ① | 涉外 IO 过 Sandbox | 本节无新涉外 IO（CLI 只读写本地文件/本地库；工具 Map 为空，检查位仍在 ToolExecutor 待 24 节） |
| ② | 审计成败都落库 | 未触碰审计链路；OryxOsRuntime 把两个 Jpa auditor 装配进运行链 |
| ③ | 无明文 key | grep 零命中；`${DEEPSEEK_API_KEY:}` 占位 + 缺失时启动点名报错（实测截屏于冒烟） |
| ④ | session_id 只在 SessionManager 拼 | grep `+ ":" +` 全库唯一命中 `JpaSessionManager.sessionId()`；CLI/Channel 只传三元组 |
| ⑤ | 无异步编程模型 | grep CompletableFuture/Reactor/线程池零命中；chat 循环同步阻塞 |
| ⑥ | 无 Spring AI 自动执行 | 无 ChatClient；装配走 `SpringAiProviderServiceImpl`（proxyToolCalls=TRUE 唯一路径） |

### 6. fat jar 冒烟实录（可自动化部分已跑）✅

- 根/12 子命令 `--help` 全部正常；未知命令 Picocli 报错 + 建议（"Did you mean: oryxos session or oryxos serve?"）
- `init` 0.35s / `profile list` 0.36s **秒回**（零 Spring）；init 幂等（重跑不覆盖）
- `provider list` 列出 deepseek（修正了 fat jar 双 application.yml 冲突：运行配置统一归 boot）
- `session list` 库不存在 → "暂无会话"，不抛异常
- chat 无 key 启动 → `provider deepseek 的 api-key 未配置或环境变量未解析`（16 节 validate 点名路径生效）
- **chat 启动日志 "Found 3 JPA repository interfaces"**（课件坑四正面证据，N=3：LlmCall/ToolInvocation/Session）

## 剩余人工项（harness 判不了，等你过）

1. **真模型多轮对话**：`DEEPSEEK_API_KEY=xxx java -jar oryxos-boot/target/*.jar chat`——多轮问答、`/quit` 正常退出、隔天再进历史还在（Demo 一对话版从头走通）；
2. **三模式共享存储体感**：chat 留下的会话在 `session list` 可见（同库同路径已由配置保证，26 节 serve 就位后补 REST 侧验证）；
3. **轻命令秒回体感**：终端实际敲 `oryxos profile list` 目测无启动等待（脚本计时 0.36s 已录）。

## 备注

- 冒烟揪出一个 analyze 预言的真问题：fat jar 内 boot 与 cli 两份 application.yml 冲突导致 provider 配置读不到——已修正（cli 不带 yml，配置统一归 boot；chat 用 `SpringApplicationBuilder.web(NONE)` 不占端口）。
- boot 遗留 `ddl-auto: create-drop` 已纠正为 `none` + `sql.init.mode=always`（重启不再毁数据，宪法 VIII）。
- 未 commit/push——同步时机由你决定。
