# Provider Config Source-of-Truth Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Issue #42，使 YAML 只首次播种缺失 Provider，SQLite `ProviderRegistry` 成为唯一运行时事实源，并证明管理台配置跨重启不被覆盖。

**Architecture:** 在 `oryxos-provider` 增加一个只校验最终注册表的 `ProviderRegistryValidator`，以及一个只播种缺失且有效 YAML 条目的 `ProviderRegistryBootstrap`。`OryxOsRuntime` 负责把两者接入注册表创建流程，Servlet 启动检查和 `chat` 复用同一个 Validator；一个默认 CI 会执行的双 Spring Context 测试证明数据库配置跨重启保持不变。

**Tech Stack:** Java 21、Spring Boot 3.x、Spring MVC、Spring Data JPA、SQLite、JUnit 5、Mockito、Maven 多模块。

## Global Constraints

- Java 运行时必须是 21；保持同步阻塞模型，不引入 Reactor、WebFlux 或 `CompletableFuture`。
- SQLite `ProviderRegistry` 是唯一运行时事实源；YAML 只首次播种数据库中不存在的 Provider。
- 数据库已有同名 Provider 时，启动代码不得调用 `ProviderRegistry.save()` 覆盖它。
- 普通 Provider 的空白或未解析 YAML API key 不得写入数据库；`mock` 继续免 key/base URL。
- `serve`、`gateway`、`chat` 校验最终注册表；不调用模型的轻命令不做 Provider 可用性校验。
- 历史无效数据库记录只报错，不自动修复、删除或用 YAML 覆盖。
- 日志和异常不得包含 API key、请求头或其他凭证值。
- 不改变 Provider REST API、Agent 配置格式、`providers` 表结构或显式名称映射机制。
- 不夹带 Issue #41、#43、#49 或其他重构。
- 保留用户已有未提交文件；每次只暂存本任务列出的精确路径。
- 执行前必须通过 `superpowers:using-git-worktrees` 为现有分支
  `codex/fix-provider-config-seeding` 创建隔离 worktree；不得在含用户未提交
  `.vscode/settings.json` 等修改的主检出目录中执行 rebase、格式化或实现。

## File Map

- Create: `oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryValidator.java` — 校验最终 `ProviderRegistry`。
- Create: `oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryValidatorTest.java` — Validator 契约测试。
- Create: `oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryBootstrap.java` — 首次播种缺失且有效的 YAML Provider。
- Create: `oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryBootstrapTest.java` — 播种、不覆盖、空 key 和 mock 回归。
- Modify: `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java:139` — 注册 Bootstrap/Validator Bean，并替换无条件 upsert。
- Modify: `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java:18` — 在进入 `CliChannel` 前校验最终注册表。
- Create: `oryxos-cli/src/test/java/io/oryxos/cli/command/ChatCommandTest.java` — 证明 `chat` 调用共享 Validator。
- Modify: `oryxos-web/src/main/java/io/oryxos/web/security/ProviderStartupCheck.java:20` — 从原始 YAML 校验改为最终注册表校验。
- Create: `oryxos-web/src/test/java/io/oryxos/web/security/ProviderStartupCheckTest.java` — 证明 Servlet 启动检查调用共享 Validator。
- Create: `oryxos-boot/src/test/java/io/oryxos/boot/ProviderConfigRestartTest.java` — 默认门禁内的跨重启 SQLite 回归。
- Modify: `oryxos-storage/src/main/resources/schema.sql:111` — 把首次播种、不覆盖已有记录的注释写明确；不改 DDL。

---

### Task 1: Validate the Effective Provider Registry

**Files:**
- Create: `oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryValidator.java`
- Create: `oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryValidatorTest.java`

**Interfaces:**
- Consumes: `ProviderRegistry.list(): List<ProviderDef>`、`ProviderDef(String name, String apiKey, String baseUrl, String description)`。
- Produces: `ProviderRegistryValidator.validate(ProviderRegistry): void`。
- Produces for Task 2: package-private `ProviderRegistryValidator.violation(ProviderDef): Optional<String>`，只返回缺失字段说明，不返回凭证值。

- [ ] **Step 1: 在 Issue #42 留下认领和实现口径评论**

通过 GitHub connector 在 `oryx-labs/oryxos#42` 发布：

```markdown
我准备处理这个 Issue，修复范围已收敛为：

1. SQLite ProviderRegistry 是唯一运行时事实源；
2. YAML 只首次播种数据库中不存在且有效的 Provider；
3. 已有同名 Provider 不被 YAML 或空环境变量覆盖；
4. serve/gateway/chat 校验最终注册表；
5. 增加默认 CI 会执行的双 Spring Context + 共享 SQLite 重启回归。

历史无效数据库记录只清晰报错，不自动覆盖或迁移。完成后会提交聚焦 PR，并以 `Closes #42` 关联。
```

确认评论创建成功并记录返回 URL；如果 GitHub connector 拒绝写入，停止外部动作并向用户报告权限阻塞，不得假称评论已经发布。

- [ ] **Step 2: 写 Validator 的失败测试**

创建 `ProviderRegistryValidatorTest`，至少包含以下断言：

```java
package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProviderRegistryValidatorTest {

  private ProviderRegistry registry;
  private ProviderRegistryValidator validator;

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    validator = new ProviderRegistryValidator();
  }

  @Test
  void validDatabaseProvider_passesWithoutReadingYaml() {
    when(registry.list())
        .thenReturn(List.of(new ProviderDef("deepseek", "db-key", "https://db.example/v1", null)));

    assertDoesNotThrow(() -> validator.validate(registry));
  }

  @Test
  void mockProvider_allowsMissingKeyAndBaseUrl() {
    when(registry.list()).thenReturn(List.of(new ProviderDef("mock", null, null, null)));

    assertDoesNotThrow(() -> validator.validate(registry));
  }

  @Test
  void emptyRegistry_failsClearly() {
    when(registry.list()).thenReturn(List.of());

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validator.validate(registry));
    assertTrue(error.getMessage().contains("Provider"));
  }

  @Test
  void blankKey_failsWithoutLeakingOtherCredentialValues() {
    String secret = "must-not-leak";
    when(registry.list())
        .thenReturn(
            List.of(
                new ProviderDef("broken", " ", "https://broken.example/v1", secret)));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validator.validate(registry));
    assertTrue(error.getMessage().contains("broken"));
    assertTrue(error.getMessage().contains("api-key"));
    assertFalse(error.getMessage().contains(secret));
  }

  @Test
  void blankBaseUrl_failsAndNamesProvider() {
    when(registry.list())
        .thenReturn(List.of(new ProviderDef("broken", "db-key", " ", null)));

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> validator.validate(registry));
    assertTrue(error.getMessage().contains("broken"));
    assertTrue(error.getMessage().contains("base-url"));
  }
}
```

- [ ] **Step 3: 运行测试并确认因 Validator 尚不存在而失败**

Run:

```bash
mvn test -pl oryxos-provider -am \
  -Dtest=ProviderRegistryValidatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL，编译错误包含 `cannot find symbol: class ProviderRegistryValidator`。

- [ ] **Step 4: 实现最小 Validator**

创建 `ProviderRegistryValidator`：

```java
package io.oryxos.provider;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ProviderRegistryValidator {

  private static final String MOCK = "mock";

  public void validate(ProviderRegistry registry) {
    List<ProviderDef> providers = registry.list();
    if (providers.isEmpty()) {
      throw new IllegalStateException("没有可用的 Provider，请先配置 Provider");
    }
    Set<String> names = new HashSet<>();
    for (ProviderDef provider : providers) {
      Optional<String> violation = violation(provider);
      if (violation.isPresent()) {
        throw new IllegalStateException(
            "provider " + safeName(provider == null ? null : provider.name()) + " " + violation.get());
      }
      if (!names.add(provider.name())) {
        throw new IllegalStateException("Provider 注册表名称重复: " + safeName(provider.name()));
      }
    }
  }

  Optional<String> violation(ProviderDef provider) {
    if (provider == null || provider.name() == null || provider.name().isBlank()) {
      return Optional.of("名称为空");
    }
    if (MOCK.equals(provider.name())) {
      return Optional.empty();
    }
    if (provider.apiKey() == null
        || provider.apiKey().isBlank()
        || provider.apiKey().contains("${")) {
      return Optional.of("的 api-key 未配置");
    }
    if (provider.baseUrl() == null || provider.baseUrl().isBlank()) {
      return Optional.of("的 base-url 未配置");
    }
    return Optional.empty();
  }

  private static String safeName(String name) {
    return name == null ? "<unknown>" : name.replace('\r', '_').replace('\n', '_');
  }
}
```

如 google-java-format 调整换行，以格式化结果为准；不得改变错误消息的无密钥属性。

- [ ] **Step 5: 运行 Validator 测试并确认通过**

Run:

```bash
mvn test -pl oryxos-provider -am \
  -Dtest=ProviderRegistryValidatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: `ProviderRegistryValidatorTest` 全部 PASS。

- [ ] **Step 6: 运行 provider 模块回归**

Run:

```bash
mvn test -pl oryxos-provider -am
```

Expected: provider 与依赖模块测试全绿，现有 `ProvidersPropertiesTest` 保持通过。

- [ ] **Step 7: 提交 Task 1**

```bash
git add \
  oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryValidator.java \
  oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryValidatorTest.java
git commit -m "fix(provider): validate effective provider registry"
```

---

### Task 2: Seed Only Missing Valid YAML Providers

**Files:**
- Create: `oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryBootstrap.java`
- Create: `oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryBootstrapTest.java`

**Interfaces:**
- Consumes: `ProviderRegistry.exists(String): boolean`、`ProviderRegistry.save(ProviderDef): ProviderDef`。
- Consumes from Task 1: `ProviderRegistryValidator.violation(ProviderDef): Optional<String>`。
- Produces: `ProviderRegistryBootstrap(ProviderRegistryValidator)`。
- Produces: `ProviderRegistryBootstrap.seedMissing(ProviderRegistry, ProvidersProperties): void`。

- [ ] **Step 1: 写 Bootstrap 的失败测试**

创建 `ProviderRegistryBootstrapTest`，覆盖“已有记录先跳过，再判断 YAML 是否有效”的顺序：

```java
package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProvidersProperties.ProviderConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProviderRegistryBootstrapTest {

  private ProviderRegistry registry;
  private ProviderRegistryBootstrap bootstrap;

  @BeforeEach
  void setUp() {
    registry = mock(ProviderRegistry.class);
    bootstrap = new ProviderRegistryBootstrap(new ProviderRegistryValidator());
  }

  @Test
  void missingValidProvider_isSeededOnce() {
    when(registry.exists("deepseek")).thenReturn(false);
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", "yaml-key", "https://seed.example/v1")));

    bootstrap.seedMissing(registry, properties);

    ArgumentCaptor<ProviderDef> saved = ArgumentCaptor.forClass(ProviderDef.class);
    verify(registry).save(saved.capture());
    assertEquals("deepseek", saved.getValue().name());
    assertEquals("yaml-key", saved.getValue().apiKey());
  }

  @Test
  void existingProvider_isNeverOverwrittenEvenWhenYamlDiffers() {
    when(registry.exists("deepseek")).thenReturn(true);
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", "yaml-old", "https://yaml.example/v1")));

    bootstrap.seedMissing(registry, properties);

    verify(registry, never()).save(any());
  }

  @Test
  void existingProvider_isKeptWhenYamlKeyIsBlank() {
    when(registry.exists("deepseek")).thenReturn(true);
    ProvidersProperties properties =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", "", "https://yaml.example/v1")));

    bootstrap.seedMissing(registry, properties);

    verify(registry, never()).save(any());
  }

  @Test
  void missingProviderWithBlankOrUnresolvedKey_isNotPersisted() {
    ProvidersProperties blank =
        new ProvidersProperties(
            List.of(new ProviderConfig("deepseek", " ", "https://seed.example/v1")));
    ProvidersProperties unresolved =
        new ProvidersProperties(
            List.of(
                new ProviderConfig(
                    "kimi", "${KIMI_API_KEY}", "https://api.moonshot.cn/v1")));

    bootstrap.seedMissing(registry, blank);
    bootstrap.seedMissing(registry, unresolved);

    verify(registry, never()).save(any());
  }

  @Test
  void mockProvider_canSeedWithoutKeyOrBaseUrl() {
    when(registry.exists("mock")).thenReturn(false);

    bootstrap.seedMissing(
        registry, new ProvidersProperties(List.of(new ProviderConfig("mock", null, null))));

    verify(registry).save(new ProviderDef("mock", null, null, null));
  }
}
```

- [ ] **Step 2: 运行测试并确认因 Bootstrap 尚不存在而失败**

Run:

```bash
mvn test -pl oryxos-provider -am \
  -Dtest=ProviderRegistryBootstrapTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL，编译错误包含 `cannot find symbol: class ProviderRegistryBootstrap`。

- [ ] **Step 3: 实现最小 Bootstrap**

创建 `ProviderRegistryBootstrap`：

```java
package io.oryxos.provider;

import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProviderRegistryBootstrap {

  private static final Logger LOG = LoggerFactory.getLogger(ProviderRegistryBootstrap.class);

  private final ProviderRegistryValidator validator;

  public ProviderRegistryBootstrap(ProviderRegistryValidator validator) {
    this.validator = validator;
  }

  public void seedMissing(ProviderRegistry registry, ProvidersProperties properties) {
    for (ProvidersProperties.ProviderConfig config : properties.providers()) {
      String name = config.name();
      if (name != null && !name.isBlank() && registry.exists(name)) {
        continue;
      }
      ProviderDef candidate =
          new ProviderDef(config.name(), config.apiKey(), config.baseUrl(), null);
      Optional<String> violation = validator.violation(candidate);
      if (violation.isPresent()) {
        LOG.warn(
            "跳过 provider {} 的启动播种: {}",
            safeName(config.name()),
            violation.get());
        continue;
      }
      registry.save(candidate);
    }
  }

  private static String safeName(String name) {
    return name == null ? "<unknown>" : name.replace('\r', '_').replace('\n', '_');
  }
}
```

关键顺序不得改变：名称有效且数据库已存在时必须先 `continue`，不能先用空 YAML key 判无效。

- [ ] **Step 4: 运行 Bootstrap 和 Validator 测试**

Run:

```bash
mvn test -pl oryxos-provider -am \
  -Dtest='ProviderRegistryBootstrapTest,ProviderRegistryValidatorTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 两个测试类全部 PASS。

- [ ] **Step 5: 运行 provider 模块回归**

Run:

```bash
mvn test -pl oryxos-provider -am
```

Expected: provider 与依赖模块测试全绿；日志测试输出不得出现测试 key 值。

- [ ] **Step 6: 提交 Task 2**

```bash
git add \
  oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryBootstrap.java \
  oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryBootstrapTest.java
git commit -m "fix(provider): seed only missing valid providers"
```

---

### Task 3: Wire Registry-First Startup and Prove Restart Safety

**Files:**
- Modify: `oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java:139-151`
- Modify: `oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java:18-32`
- Create: `oryxos-cli/src/test/java/io/oryxos/cli/command/ChatCommandTest.java`
- Modify: `oryxos-web/src/main/java/io/oryxos/web/security/ProviderStartupCheck.java:20-39`
- Create: `oryxos-web/src/test/java/io/oryxos/web/security/ProviderStartupCheckTest.java`
- Create: `oryxos-boot/src/test/java/io/oryxos/boot/ProviderConfigRestartTest.java`
- Modify: `oryxos-storage/src/main/resources/schema.sql:111-113`

**Interfaces:**
- Consumes from Task 1: `ProviderRegistryValidator.validate(ProviderRegistry): void`。
- Consumes from Task 2: `ProviderRegistryBootstrap.seedMissing(ProviderRegistry, ProvidersProperties): void`。
- Produces: Spring Beans `ProviderRegistryValidator`、`ProviderRegistryBootstrap`。
- Produces for testing: package-private static `ChatCommand.validateProviderRegistry(ConfigurableApplicationContext): void`。

- [ ] **Step 1: 写 Web 和 Chat 接线的失败测试**

创建 `ProviderStartupCheckTest`：

```java
package io.oryxos.web.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import org.junit.jupiter.api.Test;

class ProviderStartupCheckTest {

  @Test
  void validatesEffectiveRegistry() {
    ProviderRegistry registry = mock(ProviderRegistry.class);
    ProviderRegistryValidator validator = mock(ProviderRegistryValidator.class);

    new ProviderStartupCheck(registry, validator).afterSingletonsInstantiated();

    verify(validator).validate(registry);
  }
}
```

创建 `ChatCommandTest`：

```java
package io.oryxos.cli.command;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

class ChatCommandTest {

  @Test
  void validatesEffectiveRegistryBeforeConversation() {
    ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    ProviderRegistryValidator validator = mock(ProviderRegistryValidator.class);
    when(context.getBean(ProviderRegistry.class)).thenReturn(registry);
    when(context.getBean(ProviderRegistryValidator.class)).thenReturn(validator);

    ChatCommand.validateProviderRegistry(context);

    verify(validator).validate(registry);
  }
}
```

- [ ] **Step 2: 写默认门禁内的重启失败测试**

创建不带 `@Tag("integration")` 的 `ProviderConfigRestartTest`：

```java
package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

class ProviderConfigRestartTest {

  @TempDir Path root;

  @Test
  void databaseManagedProviderSurvivesRestartWithBlankYamlKey() throws Exception {
    Path workspace = Files.createDirectories(root.resolve("workspace"));
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("memory"));
    String dbUrl = "jdbc:sqlite:" + root.resolve("provider-restart.db");

    try (ConfigurableApplicationContext first =
        boot(workspace, dbUrl, "yaml-seed-key", "https://seed.example/v1")) {
      ProviderRegistry registry = first.getBean(ProviderRegistry.class);
      registry.save(
          new ProviderDef(
              "deepseek", "db-rotated-key", "https://db.example/v1", "managed"));
    }

    try (ConfigurableApplicationContext second =
        boot(workspace, dbUrl, "", "https://seed.example/v1")) {
      ProviderDef restored =
          second
              .getBean(ProviderRegistry.class)
              .find("deepseek")
              .orElseThrow();
      assertEquals("db-rotated-key", restored.apiKey());
      assertEquals("https://db.example/v1", restored.baseUrl());
      assertEquals("managed", restored.description());
    }
  }

  @Test
  void nonWebRuntimeStartsWithEmptyEffectiveRegistry() throws Exception {
    Path workspace = Files.createDirectories(root.resolve("non-web-workspace"));
    Files.createDirectories(workspace.resolve("agents"));
    Files.createDirectories(workspace.resolve("memory"));
    String dbUrl = "jdbc:sqlite:" + root.resolve("non-web.db");

    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.main.banner-mode=off",
                "oryxos.root=" + workspace,
                "spring.datasource.url=" + dbUrl,
                "oryxos.providers[0].name=deepseek",
                "oryxos.providers[0].api-key=",
                "oryxos.providers[0].base-url=https://seed.example/v1")
            .run()) {
      assertEquals(0, context.getBean(ProviderRegistry.class).list().size());
    }
  }

  private static ConfigurableApplicationContext boot(
      Path workspace, String dbUrl, String apiKey, String baseUrl) {
    return new SpringApplicationBuilder(OryxOsRuntime.class)
        .web(WebApplicationType.SERVLET)
        .properties(
            "server.port=0",
            "spring.main.banner-mode=off",
            "oryxos.root=" + workspace,
            "spring.datasource.url=" + dbUrl,
            "oryxos.providers[0].name=deepseek",
            "oryxos.providers[0].api-key=" + apiKey,
            "oryxos.providers[0].base-url=" + baseUrl)
        .run();
  }
}
```

该测试使用假 key、假 URL，不发起模型调用，不依赖网络。

- [ ] **Step 3: 运行三个新测试并确认旧接线失败**

Run:

```bash
mvn test -pl oryxos-cli,oryxos-web,oryxos-boot -am \
  -Dtest='ChatCommandTest,ProviderStartupCheckTest,ProviderConfigRestartTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL；至少包含以下一个或多个信号：

- `ProviderStartupCheck` 构造签名不匹配；
- `ChatCommand.validateProviderRegistry` 不存在；
- 重启后 API key 实际变为 YAML 空字符串或第二次 Context 启动失败。

- [ ] **Step 4: 在 `OryxOsRuntime` 注册 Bootstrap/Validator 并替换无条件 upsert**

增加 Bean：

```java
@Bean
ProviderRegistryValidator providerRegistryValidator() {
  return new ProviderRegistryValidator();
}

@Bean
ProviderRegistryBootstrap providerRegistryBootstrap(
    ProviderRegistryValidator validator) {
  return new ProviderRegistryBootstrap(validator);
}
```

把 `providerRegistry()` 改为：

```java
@Bean
ProviderRegistry providerRegistry(
    LlmProviderRepository repository,
    ProvidersProperties properties,
    ProviderRegistryBootstrap bootstrap) {
  ProviderRegistry registry = new JpaProviderRegistry(repository);
  bootstrap.seedMissing(registry, properties);
  return registry;
}
```

同时把方法注释明确为“YAML 仅首次播种缺失且有效条目；之后数据库为唯一事实源”。

- [ ] **Step 5: 把 `ProviderStartupCheck` 改为校验最终注册表**

字段和构造器改为：

```java
private final ProviderRegistry registry;
private final ProviderRegistryValidator validator;

public ProviderStartupCheck(
    ProviderRegistry registry, ProviderRegistryValidator validator) {
  this.registry = registry;
  this.validator = validator;
}

@Override
public void afterSingletonsInstantiated() {
  validator.validate(registry);
  LOG.debug(
      "Provider startup check passed ({} provider(s) configured)",
      registry.list().size());
}
```

删除对 `ProvidersProperties.validate()` 的调用和相关 import；保留
`@ConditionalOnWebApplication(SERVLET)` 与 `SmartInitializingSingleton` 时序。

- [ ] **Step 6: 把 `ChatCommand` 改为进入会话前校验最终注册表**

在 `CliChannel.run()` 之前增加：

```java
validateProviderRegistry(context);
context.getBean(CliChannel.class).run(profileName, currentUser());
```

添加测试缝：

```java
static void validateProviderRegistry(ConfigurableApplicationContext context) {
  ProviderRegistry registry = context.getBean(ProviderRegistry.class);
  context.getBean(ProviderRegistryValidator.class).validate(registry);
}
```

导入 `ProviderRegistry` 与 `ProviderRegistryValidator`。不要把校验放进
`OryxOsRuntime` Bean 创建阶段，否则 `user` 等轻命令会被阻断。

- [ ] **Step 7: 同步 `schema.sql` 注释，不改 DDL**

把 Provider 表上方注释改为：

```sql
-- 启动时仅把 config/application.yml 中数据库尚不存在且有效的 Provider 作为首次种子；
-- 已有同名记录绝不从 YAML 覆盖，之后以本表为唯一运行时事实源。
```

除注释外不修改 `CREATE TABLE providers`。

- [ ] **Step 8: 运行接线与重启测试并确认通过**

Run:

```bash
mvn test -pl oryxos-cli,oryxos-web,oryxos-boot -am \
  -Dtest='ChatCommandTest,ProviderStartupCheckTest,ProviderConfigRestartTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: 三个测试类全部 PASS；第二个 Servlet Spring Context 能在 YAML key
为空时启动，非 Web Context 能在最终注册表为空时启动。

- [ ] **Step 9: 运行受影响模块全量回归**

Run:

```bash
mvn test -pl oryxos-provider,oryxos-cli,oryxos-web,oryxos-boot -am
```

Expected: 全绿；`ProviderConfigRestartTest` 出现在默认 Surefire 结果中，不因
`integration` 标签被跳过。

- [ ] **Step 10: 提交 Task 3**

```bash
git add \
  oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java \
  oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java \
  oryxos-cli/src/test/java/io/oryxos/cli/command/ChatCommandTest.java \
  oryxos-web/src/main/java/io/oryxos/web/security/ProviderStartupCheck.java \
  oryxos-web/src/test/java/io/oryxos/web/security/ProviderStartupCheckTest.java \
  oryxos-boot/src/test/java/io/oryxos/boot/ProviderConfigRestartTest.java \
  oryxos-storage/src/main/resources/schema.sql
git commit -m "fix(provider): preserve database config across restarts"
```

---

### Task 4: Quality Gate, Push, and Pull Request

**Files:**
- Modify only if format tools require it: files already listed in Tasks 1–3.
- Read-only review: `docs/superpowers/specs/2026-08-01-provider-config-source-of-truth-design.md`
- Read-only review: `docs/superpowers/plans/2026-08-01-provider-config-source-of-truth.md`

**Interfaces:**
- Consumes: all Tasks 1–3 deliverables.
- Produces: verified branch `codex/fix-provider-config-seeding` and a PR targeting `oryx-labs/oryxos:main` with `Closes #42`.

- [ ] **Step 1: 运行格式化并检查格式化差异**

Run:

```bash
mvn spotless:apply
git status --short
git diff --check
```

Expected: Java 文件符合 google-java-format；状态中不出现用户已有
`.vscode/settings.json`、`.agents/`、`AGENTS.md`、`docs/.DS_Store`，因为这些
内容只保留在主检出目录；实现 worktree 中只应出现本计划涉及的文件。

如果 Spotless 改动了 Tasks 1–3 的 Java 文件，只暂存这些精确路径并提交：

```bash
git add \
  oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryValidator.java \
  oryxos-provider/src/main/java/io/oryxos/provider/ProviderRegistryBootstrap.java \
  oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryValidatorTest.java \
  oryxos-provider/src/test/java/io/oryxos/provider/ProviderRegistryBootstrapTest.java \
  oryxos-cli/src/main/java/io/oryxos/cli/OryxOsRuntime.java \
  oryxos-cli/src/main/java/io/oryxos/cli/command/ChatCommand.java \
  oryxos-cli/src/test/java/io/oryxos/cli/command/ChatCommandTest.java \
  oryxos-web/src/main/java/io/oryxos/web/security/ProviderStartupCheck.java \
  oryxos-web/src/test/java/io/oryxos/web/security/ProviderStartupCheckTest.java \
  oryxos-boot/src/test/java/io/oryxos/boot/ProviderConfigRestartTest.java
git commit -m "style: format provider config fix"
```

若没有格式化差异，不创建空提交。

- [ ] **Step 2: 运行完整质量门禁**

Run:

```bash
mvn clean verify
```

Expected: exit code 0；Spotless、Checkstyle、P3C/PMD、SpotBugs、FindSecBugs 和全部默认测试通过。

- [ ] **Step 3: 审查相对上游的精确差异**

Run:

```bash
git status --short --branch
git log --oneline upstream/main..HEAD
git diff --stat upstream/main...HEAD
git diff --check upstream/main...HEAD
```

Expected:

- 提交只包含设计、实现、测试和必要注释；
- 用户原有未提交文件仍未暂存；
- 不包含密钥、数据库文件、日志、`target/` 或前端构建产物；
- 分支至少包含设计提交和 Tasks 1–3 的聚焦提交。

- [ ] **Step 4: 同步最新上游并在需要时安全 rebase**

Run:

```bash
git fetch upstream
git rebase upstream/main
```

Expected: 分支基于最新 `upstream/main`；如发生冲突，只解决本计划涉及文件，
不得覆盖用户未提交内容。rebase 后重新运行：

```bash
mvn clean verify
```

Expected: exit code 0。

- [ ] **Step 5: 推送分支到 fork**

Run:

```bash
git push -u origin codex/fix-provider-config-seeding
```

Expected: 远端 fork 出现同名分支。若 rebase 后已经推送过，使用
`git push --force-with-lease origin codex/fix-provider-config-seeding`，不得使用
裸 `--force`。

- [ ] **Step 6: 创建 Pull Request**

通过 GitHub connector 创建 PR：

- Repository: `oryx-labs/oryxos`
- Base: `main`
- Head: fork 中的 `codex/fix-provider-config-seeding`
- Title: `fix(provider): preserve database config across restarts`
- Draft: `false`
- Body:

```markdown
## Summary

- treat SQLite `ProviderRegistry` as the runtime source of truth
- seed only missing, valid YAML providers without overwriting database-managed values
- validate the effective registry for serve/gateway/chat
- add a default-gate restart regression using two Spring contexts and one SQLite database

## Why

Provider bootstrap previously upserted YAML values on every startup. This could roll back
admin-managed configuration or replace a valid database API key with an empty environment
fallback.

## Testing

- `mvn test -pl oryxos-provider,oryxos-cli,oryxos-web,oryxos-boot -am`
- `mvn clean verify`

Closes #42
```

Expected: PR 创建成功并返回 URL。

- [ ] **Step 7: 在 Issue #42 回贴 PR 链接**

通过 GitHub connector 评论。第一行由固定文本“修复 PR 已提交：”与 Step 6
返回对象的真实 `display_url` 拼接，第二段固定为：

```markdown
核心回归覆盖：数据库已有有效 Provider、YAML key 为空时，重启仍保留数据库配置并正常启动。
```

发送前断言 `display_url` 以 `https://github.com/oryx-labs/oryxos/pull/` 开头；
不满足时停止并报告，不发送评论。
