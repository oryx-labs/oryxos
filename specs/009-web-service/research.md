# Research: Web Service 与第一版管理平台

无 NEEDS CLARIFICATION（三处已 H0 确认；实现口径由课件 §三 + TechSol §7 定死；端点后端服务均已核实存在）。记录关键决策。

## Decision 1：统一信封复用既有 `ApiResponse`，不引 `ErrorBody`

- **Decision**: 成功 `ApiResponse.ok(data)`、错误经 `GlobalExceptionHandler` `ApiResponse.error(status, msg)`；课件 §三、TechSol §7 里的 `ErrorBody` 反向同步为 `ApiResponse`。
- **Rationale**: `ApiResponse`（code/message/data/timestamp）第24节窗口随白名单端点预交付，已被现有 `GlobalExceptionHandler` + `SandboxWhitelist` 两个 WebMvc/单测采用（断言 `$.code`/`$.message`）。它是成功+错误通用信封，满足 TechSol"统一 JSON、含 code/message/timestamp"。引 `ErrorBody`（仅错误）会造成两套信封 + 返工既有端点/测试。
- **Alternatives**: 按课件建 `ErrorBody`——否，破坏既有测试、成功响应还得另设信封、全站两套。

## Decision 2：GlobalExceptionHandler 扩展既有类，不重写

- **Decision**: 在既有 `io.oryxos.web.GlobalExceptionHandler` 上补：`SessionNotFoundException→404`、`ProviderUnavailableException→503`、Agent 超时→504、消息超限/非法参数→400；兜底 `Exception→500` 只给统一话术、不泄漏 `e.getMessage()`。保留既有 `IllegalArgumentException→400`、`NoResourceFoundException→404`、`IllegalStateException→503` 映射。
- **Rationale**: 既有类已在、已测；本节是扩展点。500 不泄漏是关键回归（连接串/堆栈只进日志）。
- **Alternatives**: 新建 handler——否，重复 + 两个 advice 冲突。

## Decision 3：两处前序接口扩展（改造点，tasks 停点确认）

- **`SessionManager.archive(String sessionId)`**（core，18 节）+ `JpaSessionManager` 实现：`findById`→set status=`archived`、archived_at=now→save。背 `DELETE /sessions/{id}`。
  - **Rationale**: sessions 表 status(active/archived)/archived_at 列自 18 节 data model 即存在，只是没接对外入口；本节接线，属完成既有设计。
- **`MemoryService.readAll()`**（core，22 节）+ `MemoryServiceImpl` 委托 `LongTermMemoryStore.load()`。背 `GET /memory`。
  - **Rationale**: 门面现有 buildContext/remember/recall 没有"读全文"；`/memory` 语义就是返回长期记忆全文，store.load() 正是它。web 只依赖 core，故加在门面而非直接碰 store。
- **Alternatives**: 在 web 层绕过接口直接读文件/查库——否，破坏依赖方向与封装。

## Decision 4：启动只需一个 provider key —— 排除 OpenAiAutoConfiguration

- **Decision**: `application.yml` 的 `spring.autoconfigure.exclude` 追加 `org.springframework.ai.autoconfigure.openai.OpenAiAutoConfiguration`（既有已排 DashScope）。
- **Rationale**: deepseek 走 spring-ai-openai starter，其 auto-config 急切实例化 `OpenAiChatModel` 并索要 `spring.ai.openai.api-key`，OryxOS 不用它（Provider 由 `ProviderChatModelFactory` 显式构造，宪法 II/III）。不排掉 serve 启动即失败。确切全限定名以锁定 spring-ai 版本 javap/依赖树为准。
- **Alternatives**: 让运营方多配一个 key——否，违背"配置即 Agent"的低门槛、卡 31 节 30 分钟部署。

## Decision 5：管理台 = Vue SPA，Spring 托 /admin，frontend-maven-plugin 绑构建

- **Decision**: `oryxos-admin-ui` skill 生成 Vue 3 + Vite 五页只读台，`base:'/admin/'`、outDir→`oryxos-web/src/main/resources/static/admin/`；`WebConfig` 对 `/admin/**` 未命中回落 `admin/index.html`（`/api/v1/**` 不受影响）；`frontend-maven-plugin` 把 `npm ci && npm run build` 绑进 `mvn package`。
- **Rationale**: 一个 jar 一个进程同时提供 REST + 管理台（27 节"两张脸"）。前端是 AI 生成 + 构建接线，非 spec-kit Java 管道产物，验收走冒烟 + 人工。
- **Alternatives**: 独立前端服务——否，部署复杂、违背"一个 jar"；VitePress——否，那是文档站，管理台是 app。

## Decision 6：/info 只报"已配置" provider，不做 live ping

- **Decision**: `GET /info` 返回运行信息 + 各 provider 名称与"已配置"状态（遍历 providerMap key），不发真实探活请求。
- **Rationale**: live ping 慢/flaky、且会真花 token；"连通状态"核心阶段以"已装配"为准即可。真实探活留扩展阶段。
- **Alternatives**: 每次 /info 探活——否，flaky + 成本。

## 依赖 / 端点后端核实（H3 预检）

- SessionManager：`getOrCreate`/`get`/`save` 已在（archive 待加）。AgentService.process 已在。ProfileRegistry.all 已在。ToolRegistry.all/asMap 已在。MemoryService buildContext/remember/recall 已在（readAll 待加）。providerMap 已在。
- oryxos-web 依赖：spring-boot-starter-web、springdoc、spring-boot-starter-test、spotbugs-annotations 已在（`mvn dependency:tree` 复核）；frontend-maven-plugin 待加。
- 语法禁区：record DTO + 传统 switch/try-catch，无增强 switch/record 模式/pattern-matching switch。
