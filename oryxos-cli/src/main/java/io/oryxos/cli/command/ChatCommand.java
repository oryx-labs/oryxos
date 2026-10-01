package io.oryxos.cli.command;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.provider.ProviderRegistryValidator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** 重命令：启动完整运行时后进入交互对话。轻重分流标准：要调模型/跑引擎才起 Spring（课件坑二）。 */
@Command(name = "chat", description = "在终端里和 Agent 交互式对话", mixinStandardHelpOptions = true)
public class ChatCommand implements Runnable {

  @Option(names = "--profile", defaultValue = "default", description = "使用的 Agent（Profile 名）")
  String profileName;

  @Override
  public void run() {
    // chat 是终端对话，不占 HTTP 端口（serve 才起 Web）；banner 关掉保持对话界面干净
    try (ConfigurableApplicationContext context =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .run()) {
      validateProviderRegistry(context);
      requireProfile(context, profileName);
      context.getBean(CliChannel.class).run(profileName, currentUser());
    }
  }

  /**
   * A missing Agent is a configuration error, not a transient turn failure. Checked here so the
   * session banner never claims a connection that cannot work, and the command exits non-zero
   * instead of reporting every turn as a per-turn error (specs/003-cli-entry/contracts/cli.md).
   */
  static void requireProfile(ConfigurableApplicationContext context, String profileName) {
    ProfileRegistry registry = context.getBean(ProfileRegistry.class);
    if (registry.get(profileName).isPresent()) {
      return;
    }
    List<String> available =
        registry.all().stream().map(Profile::name).sorted().collect(Collectors.toList());
    throw new IllegalStateException(
        "Agent 不存在: "
            + profileName
            + (available.isEmpty()
                ? "（没有已定义的 Agent）"
                : "（可用: " + String.join(", ", available) + "）"));
  }

  static void validateProviderRegistry(ConfigurableApplicationContext context) {
    ProviderRegistry registry = context.getBean(ProviderRegistry.class);
    context.getBean(ProviderRegistryValidator.class).validate(registry);
  }

  /** 核心阶段无认证体系，"当前用户"取运行环境的系统用户名（clarify 既定默认）。 */
  private static String currentUser() {
    return System.getProperty("user.name", "unknown");
  }
}
