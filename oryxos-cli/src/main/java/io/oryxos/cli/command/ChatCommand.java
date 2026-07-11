package io.oryxos.cli.command;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.cli.OryxOsRuntime;
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
      context.getBean(CliChannel.class).run(profileName, currentUser());
    }
  }

  /** 核心阶段无认证体系，"当前用户"取运行环境的系统用户名（clarify 既定默认）。 */
  private static String currentUser() {
    return System.getProperty("user.name", "unknown");
  }
}
