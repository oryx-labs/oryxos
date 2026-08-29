package io.oryxos.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第16节》验收 harness：ProfileLoaderTest。 */
class ProfileLoaderTest {

  private static final String FULL_YAML =
      """
      name: ops-agent
      description: 运维助手
      identity:
        agent_name: 运维小欧
        prompt: 你是一个专业的运维助手
      provider:
        name: deepseek
        model: deepseek-chat
        temperature: 0.7
      tools:
        - http_get
        - notify
      skills:
        - daily-pr-digest
      mcp_servers:
        - github-mcp
      channels:
        - cli
      notify_channels:
        - type: webhook
          url: ${TEAM_WEBHOOK_URL}
      schedules:
        - id: morning
          cron: "0 0 8 * * *"
          zone: Asia/Shanghai
          message: 到点了，按技能说明执行。
      bootstrap:
        - AGENTS.md
      settings:
        max_iterations: 5
        max_history_turns: 15
      """;

  @TempDir Path profilesDir;

  private static final Set<String> KNOWN_PROVIDERS = Set.of("deepseek", "kimi");

  private static final UnaryOperator<String> TEST_ENV =
      key -> Map.of("TEAM_WEBHOOK_URL", "https://hooks.example.com/team").get(key);

  private ProfileLoader loader() {
    return new ProfileLoader(profilesDir, KNOWN_PROVIDERS, TEST_ENV);
  }

  private void write(String fileName, String content) throws IOException {
    Files.writeString(profilesDir.resolve(fileName), content);
  }

  @Test
  void 合法YAML_全字段解析且蛇形键映射到位() throws IOException {
    write("ops-agent.yaml", FULL_YAML);

    Profile profile = loader().loadAll().get("ops-agent").orElseThrow();

    assertEquals("运维助手", profile.description());
    assertEquals("运维小欧", profile.identity().agentName());
    assertEquals("deepseek", profile.provider().name());
    assertEquals("deepseek-chat", profile.provider().model());
    assertEquals(0.7, profile.provider().temperature());
    assertEquals(Set.of("http_get", "notify"), Set.copyOf(profile.tools()));
    assertEquals("github-mcp", profile.mcpServers().get(0)); // mcp_servers → mcpServers
    assertTrue(profile.tools().contains("http_get"));
    assertEquals("webhook", profile.notifyChannels().get(0).type()); // notify_channels
    assertEquals("0 0 8 * * *", profile.schedules().get(0).cron());
    assertEquals("Asia/Shanghai", profile.schedules().get(0).zone());
    assertEquals("morning", profile.schedules().get(0).key());
    assertEquals("morning", profile.schedules().get(0).name());
    assertEquals(5, profile.settings().maxIterations()); // max_iterations
    assertEquals(15, profile.settings().maxHistoryTurns()); // max_history_turns
  }

  @Test
  void 未声明settings时_使用默认值且temperature可空() throws IOException {
    write(
        "minimal.yaml",
        """
        name: minimal
        provider:
          name: kimi
          model: moonshot-v1
        """);

    Profile profile = loader().loadAll().get("minimal").orElseThrow();

    assertEquals(10, profile.settings().maxIterations());
    assertEquals(20, profile.settings().maxHistoryTurns());
    assertNull(profile.provider().temperature()); // 缺省不设，用 provider 侧默认（D6）
    assertTrue(profile.tools().isEmpty());
  }

  @Test
  void 数字字段支持引号字符串并正确解析() throws IOException {
    write(
        "quoted-numbers.yaml",
        """
        name: quoted-numbers
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: "0.7"
        settings:
          max_iterations: "10"
          max_history_turns: "15"
        """);

    Profile profile = loader().loadAll().get("quoted-numbers").orElseThrow();

    assertEquals(0.7, profile.provider().temperature());
    assertEquals(10, profile.settings().maxIterations());
    assertEquals(15, profile.settings().maxHistoryTurns());
  }

  @Test
  void 数字字段非法值报错点名() throws IOException {
    write(
        "bad-temp.yaml",
        """
        name: bad-temp
        provider:
          name: deepseek
          model: deepseek-chat
          temperature: true
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("bad-temp.yaml")));

    assertTrue(ex.getMessage().contains("provider.temperature"));
    assertTrue(ex.getMessage().contains("bad-temp"));

    write(
        "bad-iter.yaml",
        """
        name: bad-iter
        provider:
          name: deepseek
          model: deepseek-chat
        settings:
          max_iterations: not-a-number
        """);

    ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("bad-iter.yaml")));

    assertTrue(ex.getMessage().contains("settings.max_iterations"));
    assertTrue(ex.getMessage().contains("not-a-number"));
  }

  @Test
  void 列表字段写成标量时报错点名() throws IOException {
    write(
        "scalar-tools.yaml",
        """
        name: scalar-tools
        provider:
          name: deepseek
          model: deepseek-chat
        tools: http_get
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("scalar-tools.yaml")));

    assertTrue(ex.getMessage().contains("tools"), ex.getMessage());
    assertTrue(ex.getMessage().contains("必须是列表"), ex.getMessage());
  }

  @Test
  void 列表字段写成映射时报错点名() throws IOException {
    write(
        "map-schedules.yaml",
        """
        name: map-schedules
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          morning:
            cron: "0 0 8 * * *"
            message: hi
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("map-schedules.yaml")));

    assertTrue(ex.getMessage().contains("schedules"), ex.getMessage());
    assertTrue(ex.getMessage().contains("必须是列表"), ex.getMessage());
  }

  @Test
  void notify_channels条目非对象时报错点名() throws IOException {
    write(
        "scalar-notify.yaml",
        """
        name: scalar-notify
        provider:
          name: deepseek
          model: deepseek-chat
        notify_channels:
          - webhook
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("scalar-notify.yaml")));

    assertTrue(ex.getMessage().contains("scalar-notify"), ex.getMessage());
    assertTrue(ex.getMessage().contains("notify_channels"), ex.getMessage());
    assertTrue(ex.getMessage().contains("非对象条目"), ex.getMessage());
  }

  @Test
  void schedules条目非对象时报错点名() throws IOException {
    write(
        "scalar-sched.yaml",
        """
        name: scalar-sched
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - morning
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("scalar-sched.yaml")));

    assertTrue(ex.getMessage().contains("scalar-sched"), ex.getMessage());
    assertTrue(ex.getMessage().contains("schedules"), ex.getMessage());
    assertTrue(ex.getMessage().contains("非对象条目"), ex.getMessage());
  }

  @Test
  void notify_channels合法对象仍可加载() throws IOException {
    write(
        "ok-notify.yaml",
        """
        name: ok-notify
        provider:
          name: deepseek
          model: deepseek-chat
        notify_channels:
          - type: webhook
            url: https://example.com/hook
        """);

    Profile profile = loader().parse(profilesDir.resolve("ok-notify.yaml"));
    assertEquals(1, profile.notifyChannels().size());
    assertEquals("webhook", profile.notifyChannels().get(0).type());
  }

  @Test
  void schedules合法对象仍可加载() throws IOException {
    write(
        "ok-sched.yaml",
        """
        name: ok-sched
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: morning
            name: Morning job
            cron: "0 0 8 * * *"
            message: hi
        """);

    Profile profile = loader().parse(profilesDir.resolve("ok-sched.yaml"));
    assertEquals(1, profile.schedules().size());
    assertEquals("morning", profile.schedules().get(0).key());
  }

  @Test
  void 引用不存在的provider_报错信息包含该名字() throws IOException {
    write(
        "bad-provider.yaml",
        """
        name: bad-provider
        provider:
          name: nonexistent-llm
          model: some-model
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("bad-provider.yaml")));

    assertTrue(ex.getMessage().contains("nonexistent-llm")); // 点名，不许含糊
  }

  @Test
  void 坏YAML文件被跳过_其余Profile正常加载() throws IOException {
    write("good.yaml", FULL_YAML);
    write("broken.yaml", "name: [未闭合的{{{ 语法错误");
    write(
        "unknown-provider.yaml",
        """
        name: unknown
        provider:
          name: nobody
          model: m
        """);

    ProfileRegistry registry = loader().loadAll(); // 不抛异常——坏文件不阻断启动（SC-007）

    assertTrue(registry.get("ops-agent").isPresent());
    assertTrue(registry.get("broken").isEmpty());
    assertTrue(registry.get("unknown").isEmpty());
    assertEquals(1, registry.all().size());
  }

  @Test
  void 改model字段_重新加载后生效_零代码换模型() throws IOException {
    write("ops-agent.yaml", FULL_YAML);
    assertEquals(
        "deepseek-chat", loader().loadAll().get("ops-agent").orElseThrow().provider().model());

    write("ops-agent.yaml", FULL_YAML.replace("model: deepseek-chat", "model: deepseek-reasoner"));

    // 只改配置、重新加载即生效——SC-004（model 随请求传递已由 ProviderServiceTest 覆盖）
    assertEquals(
        "deepseek-reasoner", loader().loadAll().get("ops-agent").orElseThrow().provider().model());
  }

  @Test
  void 环境变量占位_从环境解析() throws IOException {
    write("ops-agent.yaml", FULL_YAML);

    Profile profile = loader().loadAll().get("ops-agent").orElseThrow();

    assertEquals(
        "https://hooks.example.com/team", profile.notifyChannels().get(0).config().get("url"));
  }

  @Test
  void 定时配置的key和展示名称被解析() throws IOException {
    write(
        "key-and-name.yaml",
        """
        name: key-and-name
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: daily
            name: Daily digest
            cron: "0 0 8 * * *"
            message: summarize yesterday
        """);

    Profile.ScheduleConfig schedule =
        loader().loadAll().get("key-and-name").orElseThrow().schedules().get(0);

    assertEquals("daily", schedule.key());
    assertEquals("Daily digest", schedule.name());
  }

  @Test
  void 定时配置不能同时给出不同的id和key() throws IOException {
    write(
        "conflicting-key.yaml",
        """
        name: conflicting-key
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - id: legacy-daily
            key: daily
            name: Daily digest
            cron: "0 0 8 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("conflicting-key.yaml")));

    assertTrue(exception.getMessage().contains("id"));
    assertTrue(exception.getMessage().contains("key"));
  }

  @Test
  void 同一Agent不能重复定义定时key() throws IOException {
    write(
        "duplicate-key.yaml",
        """
        name: duplicate-key
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: daily
            name: Morning digest
            cron: "0 0 8 * * *"
          - key: daily
            name: Evening digest
            cron: "0 0 18 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("duplicate-key.yaml")));

    assertTrue(exception.getMessage().contains("daily"));
  }

  @Test
  void 定时key和展示名称不能为空() throws IOException {
    write(
        "blank-schedule-fields.yaml",
        """
        name: blank-schedule-fields
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: " "
            name: " "
            cron: "0 0 8 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("blank-schedule-fields.yaml")));

    assertTrue(exception.getMessage().contains("key"));
  }

  @Test
  void 定时展示名称不能为空() throws IOException {
    write(
        "blank-schedule-name.yaml",
        """
        name: blank-schedule-name
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: daily
            name: " "
            cron: "0 0 8 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("blank-schedule-name.yaml")));

    assertTrue(exception.getMessage().contains("name"));
  }

  @Test
  void legacyIdMayMatchKeyWhenNameIsExplicit() throws IOException {
    write(
        "matching-id-and-key.yaml",
        """
        name: matching-id-and-key
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - id: daily
            key: daily
            name: Daily digest
            cron: "0 0 8 * * *"
        """);

    Profile.ScheduleConfig schedule =
        loader().loadAll().get("matching-id-and-key").orElseThrow().schedules().get(0);

    assertEquals("daily", schedule.key());
    assertEquals("Daily digest", schedule.name());
  }

  @Test
  void keyOnlyScheduleStillRequiresName() throws IOException {
    write(
        "key-only-without-name.yaml",
        """
        name: key-only-without-name
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: daily
            cron: "0 0 8 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("key-only-without-name.yaml")));

    assertTrue(exception.getMessage().contains("name"));
  }

  @Test
  void 新key格式即使id相同也必须显式给出name() throws IOException {
    write(
        "key-without-name.yaml",
        """
        name: key-without-name
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - id: daily
            key: daily
            cron: "0 0 8 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("key-without-name.yaml")));

    assertTrue(exception.getMessage().contains("name"));
  }

  @Test
  void scheduleKeyYamlBooleanWordRejected() throws IOException {
    write(
        "bool-schedule-key.yaml",
        """
        name: bool-schedule-key
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: on
            name: morning
            cron: "0 0 8 * * *"
        """);

    ProfileValidationException exception =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("bool-schedule-key.yaml")));

    assertTrue(
        exception.getMessage().contains("字符串") || exception.getMessage().contains("Boolean"));
  }

  @Test
  void scheduleKeyQuotedYesOk() throws IOException {
    write(
        "quoted-yes-key.yaml",
        """
        name: quoted-yes-key
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: "yes"
            name: yes-job
            cron: "0 0 8 * * *"
            message: hi
        """);

    Profile profile = loader().parse(profilesDir.resolve("quoted-yes-key.yaml"));
    assertEquals("yes", profile.schedules().get(0).key());
  }

  @Test
  void 定时配置缺少cron时报错点名() throws IOException {
    write(
        "missing-cron.yaml",
        """
        name: missing-cron
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: morning
            name: Morning digest
            message: run now
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("missing-cron.yaml")));

    assertTrue(ex.getMessage().contains("morning"), ex.getMessage());
    assertTrue(ex.getMessage().contains("cron"), ex.getMessage());
  }

  @Test
  void 定时配置cron为空字符串时报错点名() throws IOException {
    write(
        "blank-cron.yaml",
        """
        name: blank-cron
        provider:
          name: deepseek
          model: deepseek-chat
        schedules:
          - key: evening
            name: Evening digest
            cron: ""
            message: run now
        """);

    ProfileValidationException ex =
        assertThrows(
            ProfileValidationException.class,
            () -> loader().parse(profilesDir.resolve("blank-cron.yaml")));

    assertTrue(ex.getMessage().contains("evening"), ex.getMessage());
    assertTrue(ex.getMessage().contains("cron"), ex.getMessage());
  }
}
