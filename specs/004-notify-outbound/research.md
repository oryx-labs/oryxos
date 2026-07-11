# Research: Notify——结果主动送出去的统一出口

**Date**: 2026-07-11 | **Feature**: [spec.md](./spec.md)

## D1. 抽象与实现：接口先行，一档 webhook

- **Decision**: `NotifyChannelAdapter`（唯一方法 `void send(NotifyTarget target, String content)`）+ `NotifyTarget`（record：`channelType` + `Map<String,String> config`，compact ctor `Map.copyOf` 防御）。核心阶段唯一实现 `WebhookNotifyAdapter`：`config.get("url")` 为目标（缺失 → `IllegalArgumentException` 点名，不发请求），`RestClient.post().uri(url).contentType(APPLICATION_JSON).body(Map.of("content", content)).retrieve().toBodilessEntity()`。
- **Rationale**: 课件字面量签名逐字保真；接口语汇零渠道特有词（FR-001）；企微/飞书/钉钉群机器人都收通用 webhook，一档覆盖核心场景。
- **Alternatives considered**: 逐家专用 API（签名/AccessToken——课件明文留扩展阶段）；send 返回状态对象（失败要显式抛，返回值会被忽略——课件 void + 异常口径）。
- **验证方式**: javap 已实核 RestClient 全链 API（spring-web 6.1.5 本地 jar）。

## D2. 失败口径：非成功即抛

- **Decision**: RestClient `retrieve()` 默认对 4xx/5xx 抛 `RestClientResponseException`——直接依赖默认行为，不注册 onStatus 吞错处理器；连接失败抛 `ResourceAccessException`。全部上抛，由 17 节 ToolExecutor 转失败 ToolResult 并落 success=false 审计（FR-004 既有路径）。
- **Rationale**: "发出去没送到"与"没发出去"对 Agent 同义（clarify 2）；ToolExecutor 已实现"工具异常 → 失败结果 + 审计"，notify 零新增审计逻辑。
- **Alternatives considered**: 自定义异常包装（多一层类型无信息增益）。
- **验证方式**: WebhookNotifyAdapterTest 假 webhook 返回 500 断言抛出。

## D3. NotifyTools 形态：OryxTool 实现（⚠️ 停点确认项）

- **Decision**: `NotifyTools implements OryxTool`：`getName()="notify"`、`getDescription()="把一条消息推送到当前 Agent 配置好的通知渠道"`（课件 @Tool description 原文）、`getInputSchema()` 手写 JSON Schema（content 必填 string、channel 可选 string）、`execute(JsonNode input)`：取 content（缺失 → 失败 ToolResult）→ `resolveChannel(channel)` → 沙箱检查位注释（24 节 `Sandbox.enforce(HTTP_REQUEST, url)` 接线，与 http_post 共享白名单）→ `adapter.send(target, content)` → `ToolResult.ok("已推送")`。构造注入 `NotifyChannelAdapter`。
- **Rationale**: 课件"实现顺序说明"明文 @Tool 注册机制归 20 节；17 节 ToolExecutor 消费 `Map<String,OryxTool>`——OryxTool 形态**本节即可被执行与审计**（装配进 OryxOsRuntime 的 tools Map 即上线），@Tool 形态本节无消费方。渠道解析语义按 clarify 1。
- **Alternatives considered**: 照抄课件 @Tool 注解方法（本节接不了线还引 Spring AI 进 tool 模块）；等 20 节一起交付（交付物清单点名 NotifyTools，课件也明示"骨架本节落"）。
- **验证方式**: NotifyToolsTest（mock adapter）第一批可测集。

## D4. 渠道解析：ProfileContext 静态 ThreadLocal 适配

- **Decision**: 解析逻辑为 NotifyTools 私有方法 `resolveChannel(String channel)`：`ProfileContext.current()` 为 null → 报错"当前无 Agent 上下文"；`profile.notifyChannels()` 空 → 报错点名 Profile 名（课件守点：不是静默失败）；channel 空白或 `"default"` → 第一个；否则按 `NotifyChannel.type` 匹配，匹配不到 → 报错点名。命中后转 `NotifyTarget(type, config)`。全部"报错"= 返回失败 ToolResult（execute 内不抛业务异常——查询类失败模型可自救，口径同 ToolExecutor 未注册工具）。
- **Rationale**: 课件示意代码的 `profileContext.resolveNotifyChannel(channel)` 是实例方法写法，但 17 节已交付的 ProfileContext 是静态 ThreadLocal（set/current/clear）——**不改前序公共接口**（软门禁 4），解析逻辑归属调用方 NotifyTools；16 节 `Profile.NotifyChannel(type, config)` 直接映射 NotifyTarget，字段零转换。
- **Alternatives considered**: 给 ProfileContext 加实例/静态 resolveNotifyChannel（改 17 节交付物且把 Profile 业务塞进上下文持有类，职责错位）。
- **验证方式**: NotifyToolsTest 用 ProfileContext.set/clear 包裹（@AfterEach 必 clear，防 ThreadLocal 串号——17 节同款纪律）。

## D5. 假 webhook：JDK HttpServer 替代 MockWebServer（⚠️ 停点确认项）

- **Decision**: WebhookNotifyAdapterTest 用 `com.sun.net.httpserver.HttpServer`（JDK 内置公共 API）起本地端口（port 0 自动分配）：handler 记录请求方法/路径/body 并按用例返回 200/500；测试断言收到 POST、body 含 content、URL 来自 NotifyTarget.config。
- **Rationale**: 课件提及 MockWebServer——意图是"本地假 webhook、不算外网依赖、仍是单测层"；JDK HttpServer 同样满足全部意图且**零新第三方依赖**（MockWebServer 需向根 pom 加 okhttp/mockwebserver 两个 GAV，触软门禁 6 且扩大 OWASP 扫描面）。
- **Alternatives considered**: okhttp MockWebServer（API 顺手但引新依赖）；Spring MockRestServiceServer（不走真 HTTP 栈，验不到序列化与网络层）。
- **验证方式**: 测试本身；`com.sun.net.httpserver` 为 JDK 21 标准模块 jdk.httpserver 公共 API（非 sun.* 内部包，Checkstyle IllegalImport 不拦）。

## D6. 依赖与装配

- **Decision**: oryxos-tool pom：`spring-web`（RestClient，Boot BOM 版本）+ `spring-boot-starter-test`(test)。不加 spring-context/@Component——模块保持纯 POJO，装配统一走 OryxOsRuntime（20 节 ToolRegistry 接线时把 NotifyTools 放进 tools Map；本节不动 OryxOsRuntime，工具 Map 维持空）。`WebhookNotifyAdapter` 构造注入 `RestClient`（生产 `RestClient.create()`，测试同款指向假 webhook）。
- **Rationale**: 16/17/18 节一致的装配模式；本节接线进运行时不属于交付物（20 节 ToolRegistry 统一收编，避免两次改 OryxOsRuntime）。
- **Alternatives considered**: 本节就改 OryxOsRuntime 把 notify 放进 Map（可跑通但 20 节 Registry 会重构装配，改两次；且 chat 场景无 Sandbox 时提前放开涉外工具，安全上更差——检查位还没真拦截）。
- **验证方式**: `mvn dependency:tree -pl oryxos-tool | grep spring-web` 确认 BOM 解析。
