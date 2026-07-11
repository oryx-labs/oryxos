# Tasks: Notify——结果主动送出去的统一出口

**Input**: Design documents from `/specs/004-notify-outbound/`（plan.md / research.md D1~D6 / data-model.md / contracts/notify.md / quickstart.md）

**Tests**: 课件"验收 harness"分两批——第一批（WebhookNotifyAdapterTest 全量 + NotifyToolsTest 可测集）本节全落；**InOrder 白名单顺序回归（`发送前必须先过白名单校验`）明确留 24 节**，20 节接 ToolRegistry、24 节接 Sandbox 后补跑。测试方法名英文 + `@DisplayName` 保课件中文原名。

**Organization**: 抽象与 webhook 实现（US1/US2/US4）→ notify 工具骨架（US3）→ Polish。本节不改 OryxOsRuntime（20 节 ToolRegistry 统一接线）。

## Phase 1: Setup

- [x] T001 记录改造前基线：`mvn test -pl oryxos-core,oryxos-provider,oryxos-storage -am -q` 确认 16/17/18 节 63 测试全绿
- [x] T002 oryxos-tool pom 依赖补齐：`oryxos-tool/pom.xml` +`spring-web`（RestClient，Boot BOM 版本）+`spring-boot-starter-test`(test scope)；补后 `mvn dependency:tree -pl oryxos-tool | grep spring-web` 确认解析

## Phase 2: US1+US2+US4 出站抽象与 webhook 实现（P1，harness 先行）

**Goal**: 接口先行 + 一档 webhook；发送失败绝不装成功；换目标零代码。
**Independent Test**: `mvn test -pl oryxos-tool -am -Dtest='WebhookNotifyAdapterTest'` 全绿。

- [x] T003 [US1] WebhookNotifyAdapterTest：`oryxos-tool/src/test/java/io/oryxos/tool/notify/WebhookNotifyAdapterTest.java`——JDK `com.sun.net.httpserver.HttpServer` 起本地假 webhook（port 0 自动分配，handler 记录方法/body 并按用例回 200/500）：①发送后收到恰好一次 POST、body 含 content、Content-Type 为 JSON（US1）；②URL 来自 `NotifyTarget.config` 非硬编码——两个不同 url 的 target 分别发往各自地址（US4，`@DisplayName("换通知目标只改配置零代码")`）；③假 webhook 返回 500 → 异常上抛不吞（US2，`@DisplayName("webhook返回5xx_异常向上抛不静默吞掉")`）；④config 缺 url → IllegalArgumentException 点名、假 webhook 零请求
- [x] T004 [P] [US1] 抽象两件：`oryxos-tool/src/main/java/io/oryxos/tool/notify/NotifyChannelAdapter.java`（接口，唯一方法 `void send(NotifyTarget target, String content)`——课件字面量签名）+ `NotifyTarget.java`（record：channelType + Map<String,String> config，compact ctor Map.copyOf；接口语汇零渠道特有词）
- [x] T005 [US1] WebhookNotifyAdapter：`oryxos-tool/src/main/java/io/oryxos/tool/notify/WebhookNotifyAdapter.java`——构造注入 RestClient；`config.get("url")` 缺失抛 IllegalArgumentException 点名不发请求；`post().uri(url).contentType(APPLICATION_JSON).body(Map.of("content", content)).retrieve().toBodilessEntity()`（研发决策 D1/D2：非 2xx 走 RestClient 默认异常上抛，不注册吞错处理器）
- [x] T006 [US2] 阶段门禁：`mvn test -pl oryxos-tool -am -Dtest='WebhookNotifyAdapterTest' -Dsurefire.failIfNoSpecifiedTests=false` 绿，红了当场修

## Phase 3: US3 notify 工具骨架（P2）

**Goal**: OryxTool 形态骨架：渠道解析 + 沙箱检查位 + 委托发送；未配置绝不静默失败。
**Independent Test**: `mvn test -pl oryxos-tool -am -Dtest='NotifyToolsTest'` 全绿。

- [x] T007 [US3] NotifyToolsTest（第一批可测集）：`oryxos-tool/src/test/java/io/oryxos/tool/builtin/NotifyToolsTest.java`——mock NotifyChannelAdapter，@BeforeEach `ProfileContext.set(profile)`、@AfterEach 必 `ProfileContext.clear()`（防 ThreadLocal 串号）：①`notify_channels` 未配置 → 失败 ToolResult 点名、adapter 零调用（`@DisplayName("notify_channels未配置_明确报错不静默失败")`）；②channel 缺省/空白/"default" → 取第一个渠道（`@DisplayName("channel参数缺省_取第一个渠道")`）；③channel 指定第二个渠道类型 → 命中第二个；④指定类型不存在 → 失败点名、零调用；⑤ProfileContext 无值 → 失败点名；⑥content 缺失 → 失败点名；⑦成功路径 → `verify(adapter).send(目标匹配, "hello")` 且结果 content="已推送"。**文件头注释注明：InOrder 白名单顺序回归（发送前必须先过白名单校验）待 24 节 Sandbox 就位后补入本类**
- [x] T008 [US3] NotifyTools：`oryxos-tool/src/main/java/io/oryxos/tool/builtin/NotifyTools.java`——implements OryxTool（研发决策 D3/D4）：getName()="notify"、getDescription()="把一条消息推送到当前 Agent 配置好的通知渠道"、getInputSchema() 手写 JSON Schema（content 必填/channel 可选）；execute：content 缺失→失败；resolveChannel 私有方法（ProfileContext.current() null→失败；notifyChannels 空→失败点名 Profile；channel 空白或"default"→第一个；否则按 type 匹配、未命中→失败点名）；**沙箱检查位注释（24 节 `Sandbox.enforce(HTTP_REQUEST, url)` 接线，与 http_post 共享白名单）**；adapter.send → `ToolResult.ok("已推送")`
- [x] T009 [US3] 阶段门禁：`mvn test -pl oryxos-tool -am` 全绿

## Phase 4: Polish & 收尾

- [x] T010 全仓硬门禁：`mvn clean verify` 全绿（含 Spotless/P3C/Checkstyle/SpotBugs/FindSecBugs），红了修实现不改规则
- [x] T011 H4 六条全局不变量自查 + 课件"本节交付物"逐项 ls/grep 存在性核对（含"接口语汇零渠道特有词" grep 自查：主代码无 wecom/feishu/dingtalk 字样）
- [x] T012 验收报告：`specs/004-notify-outbound/acceptance-report.md`（六项证据 DoD + 分批说明 + 剩余人工项：真实 webhook 验配置、接口中立性思维自查、20/24 节待补回归清单）

## Dependencies

- T001/T002 先行；T003 先于或伴随 T004/T005（harness 先行）；T007 先于或伴随 T008。
- Phase 2 → Phase 3（NotifyTools 依赖 NotifyChannelAdapter/NotifyTarget 类型）→ Polish。
- 并行机会：T004 与 T003 不同文件可并行。

## Implementation Strategy

- MVP = Phase 2（抽象 + webhook + 失败口径，US1/US2/US4 一次覆盖）；Phase 3 补工具骨架（US3）。
- 每阶段门禁当场修红；本节结束时 oryxos-tool 首次有实码，全仓测试数 63 + 本节新增。
