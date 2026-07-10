package io.oryxos.core.profile;

import java.util.List;
import java.util.Map;

/**
 * 一个 Agent 的完整配置载体（YAML 解析产物，不可变）。
 *
 * <p>第 16 节建全全部字段；本节只消费 provider 段，其余字段供后续各节取用。 集合字段一律防御性拷贝，杜绝外部可变引用。
 */
public record Profile(
    String name,
    String description,
    Identity identity,
    ProviderRef provider,
    List<String> tools,
    List<String> skills,
    List<String> mcpServers,
    List<String> channels,
    List<NotifyChannel> notifyChannels,
    List<ScheduleConfig> schedules,
    List<String> bootstrap,
    Settings settings) {

  public Profile {
    tools = tools == null ? List.of() : List.copyOf(tools);
    skills = skills == null ? List.of() : List.copyOf(skills);
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    channels = channels == null ? List.of() : List.copyOf(channels);
    notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
    settings = settings == null ? Settings.defaults() : settings;
  }

  /** 人格设定。 */
  public record Identity(String agentName, String prompt) {}

  /** 模型选择：provider 名、model、温度（可空——缺省用 provider 侧默认，research D6）。 */
  public record ProviderRef(String name, String model, Double temperature) {}

  /** 通知渠道（19 节 NotifyTools 消费）。 */
  public record NotifyChannel(String type, Map<String, String> config) {
    public NotifyChannel {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /** 定时配置（25 节 AgentScheduler 消费）。 */
  public record ScheduleConfig(String id, String cron, String zone, String message) {}

  /** 循环参数。 */
  public record Settings(int maxIterations, int maxHistoryTurns) {
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final int DEFAULT_MAX_HISTORY_TURNS = 20;

    public static Settings defaults() {
      return new Settings(DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_HISTORY_TURNS);
    }
  }
}
