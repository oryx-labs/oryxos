---
name: oryxos-lesson-dev
description: >-
  按课件驱动开发 OryxOS 的一节课模块：输入节号（16~31），自动完成备料 → speckit-specify →
  clarify → plan → tasks（停等确认）→ implement → 节级验收报告，全程施加硬/软两类门禁，
  保证产出严格贴合 docs/TechnicalSolution.md 的 OryxOS 设计。当用户说「开发第 N 节 /
  实现第 N 节课的模块 / 用 spec kit 做第 N 节」时使用。
argument-hint: "节号（16~31），如：16"
user-invocable: true
---

# OryxOS 逐节开发 Skill

## User Input

```text
$ARGUMENTS
```

节号来自上面的输入。设计依据见 `docs/class/Harness 设计：用门禁保证每个功能符合预期.md`，流程原理见 `docs/class/Spec-Kit 执行指导：从课件到代码（以第16节为例）.md`——本 skill 是它们的可执行版本，条款以本文为准执行，不需要复读那两份文档给用户。

## 总纪律（贯穿全程）

> **能机器判的绝不留给人，机器判不了的绝不自行发挥。**

- **硬门禁**：测试、`mvn clean verify`、存在性核对——不过不放行，不许绕。
- **软门禁**：遇到下列任一情况，**立即停下、向用户报告、等确认后继续**，不得自行决定：
  1. 需要创建"本节交付物"清单之外的任何对外概念（public 类型、配置键、数据表、REST 路径、Profile 字段）；
  2. 需要修改任何已定字面量（类名、方法签名、配置键、表列名、端点路径）；
  3. 课件与 `docs/TechnicalSolution.md` 冲突；
  4. 需要修改前序节交付的公共接口（当节课件明确列为"改造点"的除外）；
  5. 第三方 API 在本地依赖中核实不到；
  6. 需要新增 plan 未列明的第三方依赖。
- **反作弊**：不得删断言、`@Disabled`、放宽阈值让测试变绿。实现错修实现；认为测试错，停下报告。未全绿不得宣称完成。
- 全程**不自动 commit / push / 运行 package.sh**，同步时机由用户决定。

---

## 第 0 步：输入校验与课型分流

解析节号 N，按下表分流；表外输入直接报错退出。

| N | 课型 | 处理 |
|---|---|---|
| 16,17,18,19,20,22,24,25,26,29,30 | 代码课 | 走完整流程（第 1~7 步） |
| 21,23 | 评审课 | 拒绝并说明：这节不产码，是下一节（22/24）的 specify 素材 |
| 27,28 | 串联课 | 特殊模式：不开新 feature，逐条执行该节课件的对账清单，并把对账固化成课件建议的 `@Tag("integration")` 测试类（27→`HumanTriggerFlowIT`；28→`SchedulerFlowIT`/`RestartRecoveryIT`），然后出报告 |
| 31 | Demo 课 | 特殊模式：按课件定义 `weather-daily`（走 30 节 API）与 `daily-tech-digest`（走手写文件）两个 Agent，完成调试对账与发布清单，不做常规模块开发 |
| 其他 | — | 报错：仅支持 16~31 |

## 第 1 步：H0 开工纪律——必读与依赖检查

1. **读三样**（用 Read 完整读，不凭记忆）：
   - 当节课件：`docs/class/第{N}节*.md`（glob 匹配）；
   - `docs/TechnicalSolution.md` 对应章节（映射表见下）；
   - 前序各代码课课件的"本节交付物"小节。
2. **依赖存在性检查**：对前序每节交付物清单里的核心类，在代码库里 grep/Glob 确认存在。任何缺失 → 停下报告"先做第 X 节"，不得跳节自造。
3. **分支**：确认当前在该节的 feature 分支上（speckit-specify 的 before_specify hook 会建分支；若 hook 未配置，手动 `git checkout -b {NNN}-lesson{N}-<slug>`），不在 main/主干上直接开发。

**技术方案章节映射表：**

| 节 | TechnicalSolution 章节 |
|---|---|
| 16 | §3（Provider）、§8.2（Profile）、§9.2（llm_calls） |
| 17 | §4（ReAct/AgentService/ProfileContext）、§8.3（ContextLoader）、§9.2（tool_invocations） |
| 18 | §8.4、§8.6（CLI）、§9.2（sessions） |
| 19 | §6.8（Notify） |
| 20 | §6.1~6.6（Tool/MCP） |
| 22 | §5（Memory） |
| 24 | §6.7（Sandbox） |
| 25 | §8.5（定时/会话身份） |
| 26 | §7（Web Service） |
| 29 | §11.1~11.2（Skill+Profile、运行时注册） |
| 30 | §11.3~11.4（AgentLifecycleService、/api/v1/agents） |
| 27/28/31 | §12（关键流程/两个 Demo） |

## 第 2 步：组装并执行 /speckit-specify

用 Skill 工具调用 `speckit-specify`，参数按此骨架从**当节课件的一、二部分**提炼（只写 WHAT/WHY，不带类名和技术栈）：

```text
第{N}节需求：<模块名>——<一句话定位>
背景与价值。<为什么需要，从课件"是什么"部分提炼>
用户场景。<2~3 个具体场景>
功能需求。FR1~FRn <从课件"想清楚"部分提炼，每条可测试>
明确不做（边界）。<课件"有几样先别做"逐项照搬>
验收标准。可自动化部分由课件"验收 harness"测试套件承载（mvn test 全绿即通过），
  关键回归点：<列出课件 harness 里写出代码的那几个测试的守点>；
  人工项见课件"五、做完怎么验"。
依赖与假设。<指向前序节交付物；外部依赖如 BOM/MCP server>
```

## 第 3 步：/speckit-clarify

调用 `speckit-clarify`。有问题答问题（答案只从课件和技术方案找，找不到 → 软门禁停下问用户）；无问题继续。

## 第 4 步：组装并执行 /speckit-plan

参数 = 固定技术栈句 + 本节模块落位 + 测试策略句 + 语法禁区：

**固定技术栈句**：`JDK 21 + Spring Boot 3.x + Spring AI Alibaba（动手前先跑 mvn dependency:tree 确认锁定 BOM 里目标依赖存在）、SQLite + Spring Data JPA。凭证走环境变量占位，不落明文。SQLite 用手工建表脚本，不依赖 hibernate.ddl-auto=update。`

**模块落位表**（照抄本节行，细节以 TechnicalSolution §10 为准）：

| 节 | 落位 |
|---|---|
| 16 | `Profile`/`ProfileLoader`/`ProfileRegistry`→oryxos-core；`ProviderService`/适配器→oryxos-provider；`LlmCall`+Repository→oryxos-storage |
| 17 | `ReActLoop`/`PromptBuilder`/`ToolExecutor`/`AgentService`/`ProfileContext`/`ContextLoader`→oryxos-core；`ToolInvocation`+Repository→oryxos-storage |
| 18 | `OryxOsCli`+12 命令→oryxos-cli；`CliChannel`→oryxos-channel-cli；`SessionManager` 接口→oryxos-core，`Session` 实体+Repository→oryxos-storage |
| 19 | notify 包（Adapter/Target/Webhook 实现/`NotifyTools`）→oryxos-tool |
| 20 | `OryxTool`/`ToolResult`→oryxos-core；其余（Registry/内置 Tool/MCP）→oryxos-tool |
| 22 | 全部→oryxos-memory |
| 24 | sandbox 包→oryxos-tool |
| 25 | `AgentScheduler`/`ScheduleConfig`→oryxos-core |
| 26 | Controller/异常处理/static-admin→oryxos-web |
| 29 | 运行时注册方法→oryxos-core 既有类；示例 Skill/Profile→`.oryxos/` |
| 30 | `AgentApiController`→oryxos-web；`AgentLifecycleService`→oryxos-core |

**测试策略句**（从课件"验收 harness"抄）：`测试策略按课件"验收 harness"执行：<测试类清单>（覆盖 <关键回归点>），单测默认跑、集成冒烟打 @Tag("integration") CI 跳过；实现完成的定义是 mvn clean verify 全绿。`

**语法禁区句**：`避开 P3C/ASM 解析不了的 Java 18+ 语法形态（如增强 switch 的 default -> 写法），静态检查是构建门禁。`

## 第 5 步：/speckit-tasks + 固定软停点

1. 调用 `speckit-tasks`。
2. **自动比对**：任务清单 ↔ 课件"本节交付物"（代码/测试/配置/表逐项），并确认测试任务先于或伴随对应实现任务（harness 先行）。
3. 输出比对结果（齐 / 缺什么 / 多什么），**停下等用户确认**后才进入下一步。这是流程中唯一的固定停点，不许跳过。

## 第 6 步：/speckit-analyze（建议跑）+ /speckit-implement

implement 期间逐任务执行，附加门禁：

- **写前**（H3）：涉及第三方 API 的任务，先在本地依赖核实方法存在；核实不到 → 软门禁。
- **写中**（H1/H5）：只创建交付物点名的对外概念；已定字面量逐字保真；异常不吞（catch 必落审计/日志或上抛）；不建文档外抽象层；注释只写"为什么"。
- **写后**（任务级 DoD）：实现与测试一起落地，跑该模块测试，红了当场修，不攒到最后。
- 课件"验收 harness"里**写出代码的关键回归测试必须原样落地**（中文方法名风格保持一致）。

## 第 7 步：节级收尾——六项证据 DoD

全部满足才可宣布本节完成，逐项把证据写进验收报告：

1. `mvn clean verify` 全绿（含 P3C/SpotBugs/FindSecBugs/PMD），贴关键输出；
2. 课件 harness 映射表的每个测试类存在且非空，关键回归测试逐个对号；
3. "本节交付物"逐项 ls/grep 存在性核对；
4. **前序节全部测试回归绿**（跨节契约证据）；
5. **H4 六条全局不变量逐条自查**：①涉外 IO 首行过 `Sandbox.enforce`（Sandbox 未就位的节：留调用位注明 24 节接线）②LLM 调用成败都落 `llm_calls`、工具执行成败都落 `tool_invocations` ③grep 无明文 key ④`session_id` 只在 `SessionManager` 内拼接 ⑤无 Reactor/`CompletableFuture`/自建线程池 ⑥无 Spring AI 自动工具执行路径；
6. 验收报告收尾：以上证据 + 课件"做完怎么验"的**剩余人工项清单**（真模型/真 webhook/冒烟等），明确告知用户"harness 已判卷，这几项等你人工过"。

报告完停止——commit/push/package.sh 由用户决定。
