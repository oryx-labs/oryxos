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
    assertEquals("daily-pr-digest", profile.skills().get(0)); // 第 32 节：skills 解析进 Profile
    assertEquals("webhook", profile.notifyChannels().get(0).type()); // notify_channels
    assertEquals("0 0 8 * * *", profile.schedules().get(0).cron());
    assertEquals("Asia/Shanghai", profile.schedules().get(0).zone());
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
}
