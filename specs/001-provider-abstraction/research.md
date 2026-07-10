# Research: Provider（第16节）

Phase 0 决策记录。每条：Decision / Rationale / Alternatives considered。带 ⚠ 的项列入 implement 写前核实清单（H3）。

## D1. deepseek / kimi 的接入路径：`spring-ai-openai`（库模块，非 starter）

- **Decision**: 两家均为 OpenAI 兼容端点，用 M6 BOM 中的 `spring-ai-openai` 构件，手工 `new OpenAiApi(baseUrl, apiKey)` + `new OpenAiChatModel(...)` 逐 provider 构造实例；不引入 `spring-ai-openai-spring-boot-starter`（starter 自动装配只会产出单实例且违背宪法 II 的禁 eager 装配）。
- **Rationale**: 已从本地 `spring-ai-bom-1.0.0-M6.pom` 查证 BOM 含 `spring-ai-openai`；多 provider 需要同类型多实例，手工构造是显式映射（宪法 III）的自然实现。
- **Alternatives considered**: `spring-ai-deepseek`——M6 BOM 不含（前期已验证），弃；每家单独 starter——BOM 覆盖不全且自动装配与宪法 II 冲突，弃。
- ✅ 已核实（T003，javap M6 jar）：`new OpenAiApi(String baseUrl, String apiKey)`；`new OpenAiChatModel(OpenAiApi)` / `(OpenAiApi, OpenAiChatOptions)`。

## D2. 关闭自动工具执行的机制（宪法 II 核心）

- **Decision**: M6 中把工具 schema 挂到请求但不让框架代执行的机制是 chat options 的 **`proxyToolCalls=true`**（代理工具调用：模型返回 tool call 时原样交回调用方）。以此为第一候选实现"只翻译不执行"。
- **Rationale**: M6 文档语义与本节 FR-004 完全对应；调用方式保持 `chatModel.call(new Prompt(messages, options))`。
- **Alternatives considered**: 不传工具 schema（无法满足 FR-004，弃）；用 ChatClient 的 tools API（宪法 I/II 禁，弃）。
- ✅ 已核实（T003，javap M6 jar）：`OpenAiChatOptions.builder().proxyToolCalls(true)` 存在；工具挂载 `toolCallbacks(List<FunctionCallback>)`；`FunctionCallback`（spring-ai-core M6）是接口，含 `getName/getDescription/getInputTypeSchema/call`——适配器直接实现该接口，`call` 抛 `IllegalStateException`（proxy 模式下框架不会调它，抛出即为"绝不执行"的第二道保险）。harness 的 `带工具schema调用_请求里关闭了自动执行` 为此项回归钉。

## D3. 禁用 Spring AI Alibaba 的 eager 自动装配

- **Decision**: 保留 `spring-ai-alibaba-starter` 依赖（qwen 备用），但在启动配置排除 `DashScopeAutoConfiguration`（既有做法，见 CLAUDE.md 记忆：无 key 时 eager 装配会阻断启动）；本节测试不依赖任何自动装配。
- **Rationale**: 宪法 II 明文要求；本节单测全部 mock ChatModel，不受影响。

## D4. `OryxTool` 补 `getInputSchema()`

- **Decision**: 给既有 stub 增加 `String getInputSchema()`（返回 JSON Schema 文本）。
- **Rationale**: TechnicalSolution §6.1 权威定义 OryxTool 四方法含 getInputSchema；20 节课件"动手前先检查"明确预告此坑（"这个方法不补上，Provider 翻译 Function Calling 时没地方拿参数说明"）。**软门禁标注**：OryxTool 在 16 节交付物清单之外，此改动在 tasks 停点请用户确认。
- **Alternatives considered**: 返回自定义 `JsonSchema` 类型（课件示意代码的写法）——需新增一个公共类型，违背 H5 不过度设计，且 TechSol 未定 Java 类型；返回 Jackson `JsonNode`——序列化边界更模糊。选 String 最中立。

## D5. 审计写入自身失败的处理

- **Decision**: `LlmCallAuditor` 实现内部 catch，记 ERROR 日志、不阻断模型调用主链路。
- **Rationale**: 可用性优先；核心阶段 SQLite 本地文件库失败概率极低。已记入 spec Assumptions（clarify 记录）。

## D6. temperature 缺省行为

- **Decision**: Profile 未声明 temperature 时不设置该参数，使用 provider 侧默认。已记入 spec Assumptions。

## D7. Repository 测试的建表方式

- **Decision**: `LlmCallRepositoryTest` 用 `@DataJpaTest` + 显式执行 `schema.sql` + SQLite 文件库（临时目录），`hibernate.ddl-auto=none`。
- **Rationale**: 课件 harness 明确要求"建表要走那份手工脚本，不要让 Hibernate 自动建"；`sqlite-jdbc` 与 `hibernate-community-dialects` 已在 storage pom，无新增依赖。
- **Alternatives considered**: H2 内存库——方言差异导致"测试绿、生产列名不对"，正是课件点名要避免的，弃。

## D8. Prompt 载体类型

- **Decision**: 本节定义轻量载体 `ProviderRequest`（在 oryxos-provider 内）：消息文本 + 可用工具列表（`List<OryxTool>`），`chat` 内部翻译成 Spring AI `Prompt`。课件签名 `chat(sessionId, Profile, Prompt)` 中的 "Prompt" 语义为"要发给模型的内容"，17 节 PromptBuilder 才是其真正生产者。
- **Rationale**: 直接用 Spring AI 的 `Prompt` 作为公共签名会把框架类型泄漏给 oryxos-core 的下游（17 节 ReActLoop），违背"只借管道不交控制权"。**软门禁标注**：`ProviderRequest` 是交付物清单外的公共类型，tasks 停点请用户确认（备选：签名直接收 `String userContent + List<OryxTool>` 两参，零新增类型但可扩展性差）。

## 依赖变更清单（H3 条款 6：plan 列明）

- oryxos-provider pom：**新增** `org.springframework.ai:spring-ai-openai`（版本由已锁定 BOM 管理）。
- 其余零新增：SnakeYAML（spring-boot-starter 传递）、JPA/sqlite-jdbc/方言（storage pom 已有）、Mockito/JUnit（spring-boot-starter-test）。
