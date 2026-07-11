package io.oryxos.cli.command;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;

/** 轻命令：列实例声明的 provider 清单。SnakeYAML 直读 classpath 配置，不为看一眼清单起 Spring。 */
@Command(
    name = "provider",
    description = "Provider 相关操作",
    mixinStandardHelpOptions = true,
    subcommands = ProviderListCommand.ListCommand.class)
public class ProviderListCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(name = "list", description = "列出可用 Provider", mixinStandardHelpOptions = true)
  static class ListCommand implements Runnable {
    @Override
    @SuppressWarnings("unchecked")
    public void run() {
      try (InputStream in =
          ListCommand.class.getClassLoader().getResourceAsStream("application.yml")) {
        if (in == null) {
          System.out.println("未找到 application.yml（classpath），无 provider 配置。");
          return;
        }
        Map<String, Object> root = new Yaml().load(in);
        Object oryxos = root == null ? null : root.get("oryxos");
        Object providers =
            oryxos instanceof Map ? ((Map<String, Object>) oryxos).get("providers") : null;
        if (!(providers instanceof List) || ((List<?>) providers).isEmpty()) {
          System.out.println("未声明任何 provider（application.yml 的 oryxos.providers 段）。");
          return;
        }
        for (Object item : (List<Object>) providers) {
          if (item instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) item;
            System.out.printf("%-12s %s%n", p.get("name"), p.getOrDefault("base-url", ""));
          }
        }
      } catch (IOException | RuntimeException e) {
        // 轻命令面向终端用户：报原因即可，不抛堆栈（YAML 语法错等均为 RuntimeException）
        System.err.println("读取 provider 配置失败: " + e.getMessage());
      }
    }
  }
}
