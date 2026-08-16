package io.oryxos.core.profile;

import java.util.List;
import java.util.Map;

/** Immutable configuration projection for one Agent profile. */
public record Profile(
    String name,
    String description,
    Identity identity,
    ProviderRef provider,
    List<String> tools,
    List<String> mcpServers,
    List<String> channels,
    List<NotifyChannel> notifyChannels,
    List<ScheduleConfig> schedules,
    List<String> bootstrap,
    Settings settings) {

  public Profile {
    tools = tools == null ? List.of() : List.copyOf(tools);
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    channels = channels == null ? List.of() : List.copyOf(channels);
    notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
    settings = settings == null ? Settings.defaults() : settings;
  }

  /** Compatibility constructor for callers that still pass ignoredSkills. */
  public Profile(
      String name,
      String description,
      Identity identity,
      ProviderRef provider,
      List<String> tools,
      List<String> mcpServers,
      List<String> channels,
      List<NotifyChannel> notifyChannels,
      List<ScheduleConfig> schedules,
      List<String> bootstrap,
      List<String> ignoredSkills,
      Settings settings) {
    this(
        name,
        description,
        identity,
        provider,
        tools,
        mcpServers,
        channels,
        notifyChannels,
        schedules,
        bootstrap,
        settings);
  }

  public record Identity(String agentName, String prompt) {}

  public record ProviderRef(String name, String model, Double temperature) {}

  public record NotifyChannel(String type, Map<String, String> config) {
    public NotifyChannel {
      config = config == null ? Map.of() : Map.copyOf(config);
    }
  }

  /** key locates a configuration within a Profile; name is for display only. */
  public record ScheduleConfig(String key, String name, String cron, String zone, String message) {}

  public record Settings(int maxIterations, int maxHistoryTurns) {
    private static final int DEFAULT_MAX_ITERATIONS = 10;
    private static final int DEFAULT_MAX_HISTORY_TURNS = 20;

    public static Settings defaults() {
      return new Settings(DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_HISTORY_TURNS);
    }
  }
}
