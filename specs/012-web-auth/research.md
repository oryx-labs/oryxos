# Research: Web Service 认证机制（最小 Auth）

**Feature**: 012-web-auth | **Date**: 2026-07-22

Phase 0 研究产出。三个探索 agent（storage / web / cli）已返回，所有 NEEDS CLARIFICATION 已解析。

## 1. 表结构与建表方式

**Decision**: `web_users` 表追加进 `oryxos-storage/src/main/resources/schema.sql`，`CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS`，镜像现有 `sessions`/`llm_calls` 块。`ddl-auto: none` + `spring.sql.init.mode: always`（`oryxos-boot/src/main/resources/application.yml:20-25`）保证启动幂等加载。

**Rationale**:
- 宪法原则 VIII 明确：表结构手工 `schema.sql` 唯一权威，禁 `ddl-auto=update`（SQLite `ALTER TABLE` 弱）。
- `StorageModule.java` Javadoc 同样警告。
- 现有 6 表全走 `schema.sql`，无 Flyway/Liquibase（pom 无依赖，无 `db/migration`）。

**Alternatives considered**:
- Hibernate `ddl-auto=update`：违宪，SQLite ALTER 弱，否。
- Flyway：引入新依赖 + 迁移文件目录，项目无先例，过度，否。
- 配置文件存账号（`application.yml` 或 `users.yaml`）：web 探索建议，但 spec FR-003 明确 SQLite 表 + CLI 管理 + 加账号免重启（FR-007）。配置文件改要重启，违 FR-007，否。

## 2. 实体/Repository 约定

**Decision**: `WebUser` 实体镜像 `LlmCall`（auto-increment 模式）：
- `package io.oryxos.storage;`（flat，无子包）
- `@Entity @Table(name = "web_users")`
- `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;`（surrogate key，**不**用 username 当主键——username 是 unique 列但非 PK，便于改名/删重建）
- `@Column(name = "username", nullable = false, unique = true) private String username;`
- `@Column(name = "password_hash", nullable = false) private String passwordHash;`
- `@Column(nullable = false) private boolean enabled;`
- `@Column(name = "created_at", nullable = false) private Instant createdAt;`
- `@Column(name = "updated_at", nullable = false) private Instant updatedAt;`
- `@PrePersist void onCreate() { if (createdAt==null) createdAt = Instant.now(); if (updatedAt==null) updatedAt = Instant.now(); }`
- plain getters/setters，**无 Lombok**（storage 模块全无 Lombok）
- 单行 Javadoc 注 "表结构以手工 schema.sql 为唯一权威"（镜像 `LlmCall.java:12`）
- `updatedAt` 在 service 改动时手动 `setUpdatedAt(Instant.now())`（镜像 `JpaScheduledTaskStore`，无 `@UpdateTimestamp` 约定）

**Repository**: `WebUserRepository extends JpaRepository<WebUser, Long>`，加 `Optional<WebUser> findByUsername(String)` + `boolean existsByUsername(String)`。无 `@Repository` 注解（现有 repo 全无）。无 `@Query`（Spring Data 派生方法够用）。

**Rationale**: 6 个现有实体全这模式。不引 Lombok 避免模块风格分裂。surrogate `Long id` 而非 username 当 PK——改名/删重建不丢外键，且 spec FR-003 说 username 是 unique 列。

**Alternatives**:
- username 当 PK（`String`）：改名要级联，外键风险，否。
- `AbstractEntity` 基类：项目无先例（grep 零匹配），引新约定过度，否。
- Lombok：storage 全无，分裂风格，否。

## 3. Service 层

**Decision**: `WebUserService` plain class（非 `@Service` 注解），构造注入 `WebUserRepository` + `PasswordEncoder`。放 `io.oryxos.storage` 包。在 `OryxOsRuntime` 用 `@Bean` 装配（镜像 `JpaSessionManager` 在 `OryxOsRuntime:290`）。

方法：`create(username, rawPassword)`、`delete(username)`、`changePassword(username, rawPassword)`、`disable(username)`、`list()`、`verify(username, rawPassword)→boolean`、`hasEnabledAccount()→boolean`（启动校验用，FR-006）。

**Rationale**: storage 模块现有 `Jpa*Manager`/`Jpa*Auditor`/`Jpa*Store` 全是 plain class + 构造注入 + `@Bean` 外部装配，无 `@Service` 注解。`WebUserService` 遵同模式（命名用 `Service` 因它非 core 接口实现，是独立服务，但风格一致）。

**Alternatives**:
- `@Service` 注解自动装配：storage 模块无先例（全 plain class），违模块风格，否。但 `io.oryxos` 被 `OryxOsRuntime` 扫，`@Service` 也能工作——**可由实现者定**，plan 不强求 plain class，但推荐 plain + `@Bean` 对齐 storage 风格。
- filter 直接用 `WebUserRepository`：省一层，但密码哈希/校验逻辑散落 filter，不利测试，否。

## 4. 密码哈希

**Decision**: `org.springframework.security:spring-security-crypto` 单 jar。用 `PasswordEncoderEncoderFactories.createDelegatingPasswordEncoder()` 得 `DelegatingPasswordEncoder`，hash 带 `{bcrypt}` 前缀。`PasswordEncoder` bean 由 `oryxos-web` 的 `PasswordEncoderFactory`（`@Configuration` class，`@Bean` 方法，见 tasks T009）提供，component scan（`OryxOsRuntime` 扫 `io.oryxos`）自动注册，**不**在 `OryxOsRuntime` 显式 `@Bean`。

**Rationale**:
- spec FR-009 明确要求 `DelegatingPasswordEncoder`（`{bcrypt}` 前缀，将来升 Argon2 无迁移）。
- `spring-security-crypto` 是单 jar，**非** `spring-boot-starter-security` 全套（无 filter chain / autoconfig / RBAC），不违"自实现核心、不锁框架"宪法精神。
- 版本由 Spring Boot parent BOM（3.3.5）管，无版本冲突。
- `DelegatingPasswordEncoder.matches(rawPw, "{bcrypt}...hash")` 自动识别前缀校验；`encode(rawPw)` 产出 `{bcrypt}...`。

**Alternatives considered**:
- `org.mindrot:jbcrypt`：停更多年，无算法升级机制，观感差，否。
- PBKDF2 纯 JDK：零依赖最纯，但无 `DelegatingPasswordEncoder` 升级路径，违 FR-009，否。
- Argon2（`argon2-jvm`）：带 native lib，破单 JAR 部署目标，否。
- `spring-boot-starter-security` 全套：引 autoconfig + filter chain，违"自实现核心"，否。

**依赖位置**：`PasswordEncoder` bean 由 `oryxos-web` 的 `PasswordEncoderFactory` 提供（`@Configuration` class + `@Bean` 方法，component scan 自动注册），故 `oryxos-web/pom.xml` 需加 `spring-security-crypto` 依赖（声明 bean 的模块需类可见）。`WebUserService`（在 `oryxos-storage`）构造注入 `PasswordEncoder`，故 `oryxos-storage/pom.xml` 也需加（用 encoder 哈希）。`oryxos-cli` 传递依赖 storage 即得类型，不必单独加。**总结**：`oryxos-storage/pom.xml` + `oryxos-web/pom.xml` 两处加 `spring-security-crypto`。

## 5. Filter 注册

**Decision**: `FilterRegistrationBean<BasicAuthFilter>` in `oryxos-web/config/AuthFilterConfig.java`，`addUrlPatterns("/admin/**")`，`setOrder(Ordered.HIGHEST_PRECEDENCE + 10)`。`BasicAuthFilter extends OncePerRequestFilter`。

**Rationale**:
- web 探索确认：项目无任何 filter/interceptor 先例（grep 零匹配），本 feature 引首个。
- `/admin/**`（静态 SPA，`ResourceHttpRequestHandler`）与 `/api/v1/**`（REST controller）URL 空间不交叠，`addUrlPatterns("/admin/**")` 精确保证 `/api/v1/**` 不被拦（SC-003）。
- `/api/v1/health` 天然免认证（不在 `/admin/**`），满足 FR-008。
- `OncePerRequestFilter` + `FilterRegistrationBean` 是 Spring Boot 惯用法，无需 `@WebFilter`/`@ServletComponentScan`（项目不用）。
- `WebConfig` 非 `@EnableWebMvc`（不 disable Boot 自动注册），filter 正常生效。

**Alternatives**:
- `HandlerInterceptor` + `addInterceptors`：对静态资源 handler 也生效但更绕，filter 更底层更可靠，否。
- `@WebFilter` + `@ServletComponentScan`：项目无先例，引入 servlet 扫描，否。
- Spring Security 全套 filter chain：违"自实现核心"，否。

## 6. 401 响应写法

**Decision**: filter 内直接写 `ApiResponse.error(401, "Unauthorized")` JSON 到 response（status 401 + `Content-Type: application/json` + `WWW-Authenticate: Basic realm="<配置值>"`），不抛异常。

**Rationale**: web 探索确认 `GlobalExceptionHandler`（`@RestControllerAdvice`）只捕 DispatcherServlet handler 路径内异常，filter 在 DispatcherServlet 之前跑，filter 抛异常不被 advice 捕，会冒到 servlet 容器错误页。直接写 response 最简单且统一信封。

**Alternatives**:
- 抛 `UnauthorizedException` + 转发 `/error`：复杂，要注册 error handler，否。
- 依赖 `GlobalExceptionHandler` 加 401 映射：advice 捕不到 filter 异常，无效，否。

## 7. 配置绑定

**Decision**: `WebAuthProperties` `@ConfigurationProperties(prefix = "oryxos.web.auth")` record/class，字段 `boolean enabled`（默认 false）+ `String realm`（默认 "OryxOS"）。加到 `OryxOsRuntime` 的 `@EnableConfigurationProperties({...})` 列表（镜像 `ProvidersProperties` 等四个，对齐项目约定，不用 `@Component`）。

**Rationale**:
- web 探索确认项目无 `@ConfigurationPropertiesScan`，四个现有 properties 全走 `@EnableConfigurationProperties` 显式注册。对齐此约定。
- `oryxos.web.*` 子树在所有 yml 中完全未占用（grep 零匹配），无冲突。
- `oryxos` 前缀已被 `ProvidersProperties`（`oryxos.providers`）占用，但 `oryxos.web.auth` 是不同子树，无碰撞。

**Alternatives**:
- `@ConfigurationProperties` + `@Component`：组件扫描拾取，更简，但违项目约定（现有四个全 `@EnableConfigurationProperties`），否。
- 配置文件（非 properties 类）：弱类型，否。

## 8. CLI 命令模式

**Decision**: `UserCommand` parent `@Command(name="user", subcommands={AddCommand.class, ListCommand.class, DeleteCommand.class, PasswdCommand.class, DisableCommand.class})` implements `Runnable`（bare `oryxos user` 打 usage）。5 leaf 全是 static nested class，**全是 heavy command**（仿 `ChatCommand`）：`run()` 内 `new SpringApplicationBuilder(OryxOsRuntime.class).web(WebApplicationType.NONE).bannerMode(Banner.Mode.OFF).run()` try-with-resources，`context.getBean(WebUserService.class)` 干活。

**Rationale**:
- cli 探索确认：`ProfileCommand` 是 group 命令模板（parent + static nested leaf）；`ChatCommand` 是 heavy command 模板（自启 Spring + getBean）。
- `user add/passwd` 需 `WebUserService`（BCrypt 哈希），必须 Spring 上下文；`user list/delete/disable` 也走 service（统一入口、复用校验、保持一致）。全 heavy。
- entity/repo 在 `oryxos-storage`，`OryxOsRuntime` 已 `@EnableJpaRepositories(basePackages="io.oryxos.storage")` + `@EntityScan`，自动发现 `WebUser`/`WebUserRepository`，无需改 scan 注解。
- `UserCommand.class` 注册进 `OryxOsCli` 的 `subcommands={...}` 数组（line 26-37）。

**密码输入**: `System.console().readPassword("[%s] ", prompt)`（spec FR-004/Edge Cases）。`System.console()` 返 null（piped stdin）时报错拒绝（不 fallback 明文读，避免脚本注入密码泄漏）。两次输入比对不一致拒绝（FR-011）。校验：username 非空/无空格/≤64；密码 ≥8 字符（FR-011）。明文密码 NEVER 打印/日志（宪法 VI）。

**Alternatives**:
- Light JDBC（仿 `SessionListCommand`）：走不了 `DelegatingPasswordEncoder`（需 Spring bean），且 spec FR-009 要求 encoder，否。
- leaf 用 `@Autowired` + `@Component`：Picocli 命令非 Spring bean（`OryxOsCli.main` 用 `new CommandLine(new OryxOsCli())`，无 bean factory），`@Autowired` 不生效，否。

## 9. 启动校验（FR-006）

**Decision**: `OryxOsRuntime` 或 `WebAuthProperties` 启动后（`@Bean` 装配 `WebUserService` 后）跑 `WebUserService.hasEnabledAccount()`；若 `auth.enabled=true` 且无 enabled 账号，抛 `IllegalStateException` 阻断启动（清晰 message 提示 `oryxos user add`）。

**Rationale**: spec FR-006 要求开 auth 但无账号时阻断启动，不静默裸奔。固定用 `@Component implements ApplicationRunner`（放 `oryxos-web`，与 filter 同模块便于聚合），`run()` 内判 `auth.enabled==true && !webUserService.hasEnabledAccount()` 时抛 `IllegalStateException` 阻断启动。不选 `@Bean` init 方式（启动顺序耦合 bean 装配链，`ApplicationRunner` 在 context 就绪后跑更可靠）。

## 10. DB 文件路径勘误

**发现**: spec Assumptions 写 DB 在 `.oryxos/oryxos.db`，实际 `oryxos-boot/src/main/resources/application.yml:18-19` 是 `jdbc:sqlite:oryxos.db`（工作目录根）。**plan 按实际路径**。spec 不改（spec 是 WHAT 层，路径是实现细节，plan 对齐即可）。实现时若 maintainer 要 `.oryxos/oryxos.db` 另议，当前按现状 `oryxos.db`。
