# Phase 0 Research: Health 端点暴露版本信息

**Feature**: 052-health-version | **Date**: 2026-10-14

勘察现状(见 `oryxos-web` 与根 `pom.xml`)后，解决 spec 在 Assumptions 中留给 plan 的唯一未知数：`version` / `build` 的取值来源与装配方式。

## 现状基线

- 健康端点：`SystemApiController.health()` 当前返回 `ApiResponse.ok(Map.of("status","ok"))`，即 `{code,message,data:{status:"ok"}}`。
- 同类端点：`/info` 用 `InfoView(application, providers)` record 承载结构化视图——本特性应对齐这一既有风格，用 record 做视图而非裸 Map。
- 版本来源：项目**未**配置 `spring-boot-maven-plugin` 的 `build-info` goal，也未配置 git sha 插件。项目 Maven 版本为 `0.1.8-RELEASE`（根 pom `<version>`）；注意根 pom 里的 `4.1.1` 是 `spring-boot-starter-parent` 的父版本，**不是**项目版本，`version` 字段应回显 `0.1.8-RELEASE`。
- 审计/鉴权：`/health` 在核心阶段匿名可访问（SystemApiController 类注释与 SuppressFBWarnings 已说明），本特性不得改变。

## Decision 1：version 取 Maven 项目版本号，经 build-info 注入

- **Decision**: 启用 `spring-boot-maven-plugin` 的 `build-info` goal，构建期生成 `META-INF/build-info.properties`；运行期由 Spring Boot 自动装配的 `BuildProperties` Bean 读取 `version`。
- **Rationale**: 这是 Spring Boot 官方标准做法，`BuildProperties` 开箱即用，version 天然与项目 Maven `<version>`（当前 `0.1.8-RELEASE`）同源（满足 spec "与构建产物版本同源"）。无需自写版本常量，不会与 pom 漂移。
- **Alternatives considered**:
  - 硬编码版本常量 / 读 `application.yml` 占位：会与 pom 版本漂移，违反同源，否决。
  - 读 `Implementation-Version`（jar manifest）：IDE / `mvn spring-boot:run` 下 manifest 常缺失，开发期拿不到，不如 build-info 稳定。

## Decision 2：build 取 git short sha + 构建时间，git 插件缺省则降级

- **Decision**: `build` 取 "git short sha"；短 sha 不可得时降级为 `build-info` 的构建时间戳；两者都不可得时取占位 `dev`。优先引入 `io.github.git-commit-id:git-commit-id-maven-plugin` 生成 `git.properties`，运行期读 `git.commit.id.abbrev`。
- **Rationale**: 短 sha 是区分"同版本号不同构建"的最精确标识（满足 FR-002 的意图）。构建时间与 `dev` 占位覆盖"源码直接运行/非正式构建"场景（满足 FR-004 的 Edge Case）。git-commit-id 插件是业界标准，产物 `git.properties` 读取简单。
- **Alternatives considered**:
  - 只用构建时间不用 sha：无法区分同一分钟内的两次构建，精度不足。
  - 调用外部 `git` 命令运行期取 sha：违反宪法 VI（Shell 需白名单）且运行环境未必有 git/仓库，否决。

## Decision 3：以 record 视图承载，纯增量扩展 health 返回体

- **Decision**: 新增 `HealthView(String status, String version, String build)` record（对齐 `InfoView` 风格），`health()` 返回 `ApiResponse.ok(new HealthView("ok", version, build))`。原 `status` 字段保留、取值语义不变。
- **Rationale**: 满足 FR-005/FR-006（不改路径、不动原字段语义、不新增端点）。record + `ApiResponse` 包装与现有代码一致，JSON 序列化后 `status` 仍在原位置，向后兼容（US2）。
- **Alternatives considered**:
  - 继续用 `Map.of` 追加 key：可行但与 `/info` 的 record 风格不一致，放弃以保持代码一致性。
  - 用 Spring Boot Actuator 的 `/actuator/info`：那是另一个端点，违反 FR-006"不新增端点"且改变调用契约，否决。

## Decision 4：版本信息装配为一个只读组件，与健康状态解耦

- **Decision**: 新增一个轻量 provider（如 `VersionInfoProvider`），在启动时一次性解析 version/build 并缓存为不可变值，`health()` 直接读取。`BuildProperties` / `git.properties` 缺失时在该组件内降级为占位值，绝不抛错。
- **Rationale**: 满足 FR-003（实例生命周期内稳定一致）与 Edge Case（端点不健康时版本仍可读——版本解析不依赖健康状态）。集中降级逻辑便于测试。
- **Alternatives considered**:
  - 在 controller 内联解析：降级逻辑散落、难测，否决。

## 对宪法的影响

- 原则 VII（同步执行）：纯同步读取，无异步，符合。
- 原则 VI（安全/沙箱）：不走 Shell/文件白名单路径，运行期只读 classpath 资源，符合；版本信息为非敏感公开信息。
- 原则 V（审计 Day One）：本特性是只读视图扩展，不新增工具调用/LLM 调用，不涉及审计表，N/A。
- 技术栈约束：仅新增两个标准 Maven 构建插件配置 + 一个 web 层 record/组件，不新增模块、不改 `oryxos-core` 契约，符合。

**所有 NEEDS CLARIFICATION 已解决，可进入 Phase 1。**
