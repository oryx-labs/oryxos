# Implementation Plan: 管理后台用户登录

**Branch**: `feature/admin-login` | **Date**: 2026-07-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/012-admin-login/spec.md`

## Summary

为 OryxOS 管理后台增加单一本地初始管理员登录。部署人员通过环境变量提供管理员账号和编码后的密码；服务以服务端会话维持登录状态，以 CSRF 防护所有变更请求，并在同一安全边界内保护管理 API。连续失败五次会触发 15 分钟临时限制；成功、失败、限制与退出均同步写入 SQLite 安全审计。管理台沿用既有 Vue 单页应用与视觉 token，在认证完成前只显示登录界面、不加载受保护数据。

登录页视觉由本次实现直接定稿：它应作为既有管理台的认证入口，而不是独立营销页。页面使用全屏深色工作台背景，中间偏右或居中的紧凑登录面板，左侧/顶部保留 OryxOS 品牌、运行时安全提示与少量系统状态；账号、密码、提交、错误、未配置、限制、加载和会话过期状态全部复用现有管理台 token、5px 小圆角、深色面板、橙色强调和等宽字体的信息表达。成功登录后无视觉跳变地进入原管理布局；失败时只显示统一安全提示。

## Technical Context

**Language/Version**: Java 21（后端）与 JavaScript（既有 Vue 3 管理台）

**Primary Dependencies**: Spring Boot 3.3.5、Spring MVC、Spring Security（新增）、Spring Data JPA、SQLite JDBC、Vue 3 + Vite

**Storage**: SQLite；`oryxos-storage/src/main/resources/schema.sql` 是唯一权威。新增安全审计表，不依赖 Hibernate 自动迁移；登录失败窗口为单实例内存状态。

**Testing**: JUnit 5、Mockito、MockMvc、真实 SQLite `@DataJpaTest`、`@SpringBootTest` + `TestRestTemplate` 集成测试；前端以 `npm run build` 验证打包。

**Target Platform**: 企业自有服务器/K8s 中的单个 Spring Boot JVM 与现代浏览器；管理台、API 同源部署在 `/admin` 与 `/api/v1`。

**Project Type**: Maven 多模块 Web 应用，内嵌 Vue SPA。

**Performance Goals**: 正常运行下 95% 有效登录在 2 秒内完成；认证过滤、会话读取和失败限制不引入异步链路。

**Constraints**: 不提供默认凭证、注册、找回密码、多人角色、JWT 或外部 SSO；密码与会话/CSRF 凭证不得写入日志、响应或 SQLite；未认证请求绝不触达受保护的控制器逻辑；生产部署使用 HTTPS 与安全 Cookie。

**Scale/Scope**: 一名本地初始管理员、一个完整管理访问范围、一个进程内会话与失败限制域；后续可替换身份提供者而保持 API、会话与前端操作一致。

## Constitution Check

*GATE: Passed before Phase 0 research. Re-checked after Phase 1 design: passed.*

- **I. 自实现 ReAct Loop**: 通过。认证不改变 ReActLoop、PromptBuilder 或 ToolExecutor。
- **II. Spring AI 仅做协议转换与 Schema**: 通过。新增的 Spring Security 与 Spring AI 无交集；既有自动装配排除项保持不变。
- **III. Provider 显式映射**: 通过。Provider 装配与认证无关，不改映射表。
- **IV. Agent 目录/Tool 边界**: 通过。不把管理员身份、登录流程或安全事件建模为 Agent、Skill 或 Tool。
- **V. 审计 Day One 落库**: 通过。登录成功、失败、限制与退出增加同步 SQLite 审计记录；不能只写日志。
- **VI. 安全是地基**: 通过。初始凭证仅从环境变量读取，使用编码后密码比较；会话 Cookie、CSRF、统一 401/403、失败限制和不泄露错误信息均在服务端落实。
- **VII. 同步执行 + 虚拟线程**: 通过。采用 Spring MVC 的同步过滤链和数据库访问；不引入 WebFlux、Reactor 或 `CompletableFuture`。
- **VIII. 配置即 Agent、状态外置**: 通过。认证配置显式校验；持久化审计使用手工 DDL。未新增模块，跨模块审计契约在 `oryxos-core`，由 `oryxos-storage` 实现，避免循环依赖。

## Project Structure

### Documentation (this feature)

```text
specs/012-admin-login/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── authentication-api.md
└── tasks.md                 # created later by /speckit-tasks
```

### Source Code (repository root)

```text
oryxos-core/
├── src/main/java/io/oryxos/core/auth/
│   ├── AdminAuthAuditStore.java
│   └── AdminAuthEvent.java
└── src/test/java/io/oryxos/core/auth/

oryxos-storage/
├── src/main/java/io/oryxos/storage/
│   ├── AdminAuthEventEntity.java
│   ├── AdminAuthEventRepository.java
│   └── JpaAdminAuthAuditStore.java
├── src/main/resources/schema.sql
└── src/test/java/io/oryxos/storage/

oryxos-web/
├── pom.xml
├── src/main/java/io/oryxos/web/
│   ├── auth/                 # local identity adapter, failure tracker, audit facade
│   ├── config/               # security properties and SecurityFilterChain
│   ├── controller/AuthApiController.java
│   ├── controller/dto/       # login/session/CSRF/audit views
│   └── GlobalExceptionHandler.java
├── src/main/frontend/src/
│   ├── App.vue
│   ├── api.js                # shared same-origin fetch + session-expiry handling
│   └── styles/tokens.css
└── src/test/java/io/oryxos/web/

oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java
oryxos-boot/src/main/resources/application.yml
config/application.yml.example
oryxos-boot/src/test/java/io/oryxos/boot/
```

**Structure Decision**: 认证 HTTP 流程属于现有 `oryxos-web`；安全事件是跨层审计能力，因此仅其接口和值对象放入 `oryxos-core`，SQLite 实现放入 `oryxos-storage`。`OryxOsRuntime` 继续作为实际 `serve`/`gateway` 的显式装配点，注册认证配置和审计实现。前端扩展已有单文件 SPA 与 token，不新增路由框架或设计系统。登录页作为 `App.vue` 顶层认证态的一部分实现，复用 `src/styles/tokens.css`、现有 logo 与管理台布局语汇。
