# Implementation Plan: Web Service 与第一版管理平台

**Branch**: `class-26` | **Date**: 2026-07-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-web-service/spec.md`

## Summary

给 OryxOS 装对外门面：`oryxos-web` 里建六个薄 Controller（10 个 REST 端点，统一前缀 `/api/v1`），发消息/invoke 走既有 `AgentService.process` 同一引擎；所有响应统一走既有 `ApiResponse` 信封，异常收敛到扩展后的 `GlobalExceptionHandler`（补 404/503/504/400 + 500 不泄漏）。管理台是 Vue 3 + Vite SPA（`oryxos-admin-ui` skill 生成，frontend-maven-plugin 绑进 mvn），Spring 托在 `/admin`。`application.yml` 追加排除 `OpenAiAutoConfiguration` 让 serve 只需一个 provider key。两处前序接口按设计完成扩展：`SessionManager.archive`（背 DELETE，sessions.status/archived_at 自 18 节即在）、`MemoryService.readAll`（背 GET /memory）。

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 3.x（spring-boot-starter-web、Spring MVC + 虚拟线程）、springdoc-openapi（/swagger-ui）、frontend-maven-plugin（新增，绑 npm build）、Vue 3 + Vite（前端，`oryxos-admin-ui` skill 生成）。均在 oryxos-web；动手前 `mvn dependency:tree` 核实。

**Storage**: 无表变更（sessions.status/archived_at 列自 18 节即在）；只经既有服务查询/落库。

**Testing**: `@WebMvcTest` 切片（mock 核心服务）+ `@SpringBootTest` 冒烟（`@Tag("integration")` CI 跳）；MockMvc + Mockito。

**Target Platform**: 企业内 K8s/服务器；`serve`/`gateway` 常驻。

**Project Type**: 多模块 Maven web-service；Controller/异常/WebConfig/前端 → oryxos-web；两处接口扩展 → oryxos-core（实现 → oryxos-storage/oryxos-memory）。

**Performance Goals**: 无硬指标；虚拟线程扛并发（人工压 200 并发 invoke）；Agent 调用 60s 超时。

**Constraints**: 同步阻塞 + 每请求虚拟线程（宪法 VII，无 WebFlux）；无认证（内网假设）；避开 P3C 不解析的 Java 18+ 语法。

**Scale/Scope**: 6 Controller + 若干 DTO + WebConfig（oryxos-web）；GlobalExceptionHandler 扩展；ApiResponse 复用；2 处 core 接口扩展 + 各自实现；application.yml + pom（frontend-maven-plugin）；Vue 管理台（skill 生成）；ErrorBody→ApiResponse 反向同步文档。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 相关性 | 结论 |
|---|---|---|
| II Spring AI 仅协议转换、禁 eager 装配 | serve 启动 | ✅ application.yml 排除 `OpenAiAutoConfiguration`（+ 既有 DashScope）；Provider 仍由显式映射构造 |
| V 审计 Day One | 发消息/invoke 落库 | ✅ 走 `AgentService.process` 既有 llm_calls/tool_invocations，Controller 不新增审计 |
| VII 同步 + 虚拟线程 | Web 层并发 | ✅ Spring MVC + `spring.threads.virtual.enabled`，无 Reactor/WebFlux |
| IV/§10 模块边界 | 落位 | ✅ Web 层落 oryxos-web；接口扩展落 core（依赖倒置，web 只依赖 core） |
| VI 安全是地基 | 涉外 IO/凭证 | ✅ 本节不新增涉外工具调用（发消息经 ReAct→工具→Sandbox 既有链路）；无明文 key；**注意**：管理台/REST 核心阶段无认证是设计前提（内网 + 网络层兜底），扩展阶段补 |

**Gate: PASS**（无违规）。

**改造点声明（前序公共接口扩展，需在 tasks 停点确认）**：
1. `SessionManager.archive(String sessionId)`（core，18 节接口）+ `JpaSessionManager` 实现（status=archived、archived_at=now）——背 `DELETE /sessions/{id}`。属完成 18 节 data model 已设计但未接线的归档生命周期，非新概念。
2. `MemoryService.readAll()`（core，22 节接口）+ `MemoryServiceImpl` 委托 `store.load()`——背 `GET /memory`。门面补一个只读全文方法，非新概念。

其余改造点（课件已列/H0 已确认）：`GlobalExceptionHandler` 扩展、`ErrorBody`→`ApiResponse` 反向同步、`application.yml` 加 exclude、pom 加 frontend-maven-plugin。

## Project Structure

### Documentation (this feature)

```text
specs/009-web-service/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/rest-api.md   # 10 端点契约 + ApiResponse 信封 + 错误码
└── tasks.md                # /speckit-tasks 生成
```

### Source Code (repository root)

```text
oryxos-web/src/main/java/io/oryxos/web/
├── controller/SessionApiController.java     # 【新增】POST /sessions, POST /{id}/messages, GET /{id}, DELETE /{id}
├── controller/AgentApiController.java       # 【新增】POST /agents/{name}/invoke（30 节再加 CRUD）
├── controller/ProfileApiController.java     # 【新增】GET /profiles
├── controller/MemoryApiController.java      # 【新增】GET /memory
├── controller/ToolApiController.java        # 【新增】GET /tools
├── controller/SystemApiController.java      # 【新增】GET /health, GET /info
├── controller/dto/*.java                    # 【新增】请求/响应 record DTO
├── config/WebConfig.java                    # 【新增】SPA /admin/** 回落 index.html、静态资源映射
├── GlobalExceptionHandler.java              # 【改】扩展既有：+404/503/504/400、500 不泄漏
├── common/ApiResponse.java                  # 【不改】统一信封复用
└── controller/SandboxWhitelistController.java  # 【不改】既有，共用信封

oryxos-web/src/main/frontend/                # 【新增】Vue3+Vite（oryxos-admin-ui skill 生成）→ build 到 ../resources/static/admin/
oryxos-web/pom.xml                           # 【改】+ frontend-maven-plugin

oryxos-core/src/main/java/io/oryxos/core/
├── session/SessionManager.java              # 【改·改造点1】+ archive(String)
└── memory/MemoryService.java                # 【改·改造点2】+ readAll()
oryxos-storage/.../JpaSessionManager.java    # 【改】实现 archive
oryxos-memory/.../MemoryServiceImpl.java     # 【改】实现 readAll（委托 store.load）

oryxos-boot/src/main/resources/application.yml  # 【改】autoconfigure.exclude + OpenAiAutoConfiguration

oryxos-web/src/test/java/io/oryxos/web/
├── controller/SessionApiControllerTest.java # 【新增】@WebMvcTest：32KB→400 / 不存在→404 / process 恰一次
├── GlobalExceptionHandlerTest.java          # 【新增】各异常→码 + 统一信封 + 500 不泄漏（关键回归）
└── WebSmokeIT.java                          # 【新增】@SpringBootTest @Tag(integration)：health/info/profiles/tools 可达

docs/
├── TechnicalSolution.md §7                  # 【改】ErrorBody→ApiResponse 反向同步
└── class/第26节*.md                         # 【改】§三 ErrorBody→ApiResponse 反向同步
```

**Structure Decision**: Web 层全落 oryxos-web；两处接口扩展落 oryxos-core（web 只依赖 core，依赖倒置）；前端 Vue 落 oryxos-web/frontend、build 进 static/admin。

## Complexity Tracking

> 无宪法违规，本节留空。两处接口扩展在 Constitution Check 改造点声明中列明，tasks 停点确认。
