package io.oryxos.core.profile;

import java.util.List;
import java.util.Map;

/** Immutable configuration projection for one Agent profile. */
public record Profile(
    String name,
    String description,
    Identity identity,
    Persona persona,
    ProviderRef provider,
    List<String> tools,
    List<String> mcpServers,
    List<String> channels,
    List<NotifyChannel> notifyChannels,
    List<ScheduleConfig> schedules,
    List<String> bootstrap,
    Settings settings,
    Sandbox sandbox) {

  public Profile {
    tools = tools == null ? List.of() : List.copyOf(tools);
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    channels = channels == null ? List.of() : List.copyOf(channels);
    notifyChannels = notifyChannels == null ? List.of() : List.copyOf(notifyChannels);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
    settings = settings == null ? Settings.defaults() : settings;
  }

  /**
   * Compatibility constructor for callers that still pass pre-sandbox 12 args: sandbox stays null
   * (= 继承全局执行后端).
   */
  public Profile(
      String name,
      String description,
      Identity identity,
      Persona persona,
      ProviderRef provider,
      List<String> tools,
      List<String> mcpServers,
      List<String> channels,
      List<NotifyChannel> notifyChannels,
      List<ScheduleConfig> schedules,
      List<String> bootstrap,
      Settings settings) {
    this(
        name,
        description,
        identity,
        persona,
        provider,
        tools,
        mcpServers,
        channels,
        notifyChannels,
        schedules,
        bootstrap,
        settings,
        null);
  }

  /**
   * Compatibility constructor for callers that still pass pre-persona 11 args: persona stays null.
   */
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
      Settings settings) {
    this(
        name,
        description,
        identity,
        null,
        provider,
        tools,
        mcpServers,
        channels,
        notifyChannels,
        schedules,
        bootstrap,
        settings);
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
        null,
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

  /** 人格设定（025 迁移）：结构化主人格，与 identity.prompt（自由补充）叠加注入。name/role 必填，其余可空。 */
  public record Persona(
      String name,
      String role,
      String traits,
      String tone,
      String values,
      String boundaries,
      String sampleStyle) {}

  /** fallbacks（023）：有序备用 Provider 列表，单次 LLM 调用故障时按序切换；空=零变化。 */
  public record ProviderRef(
      String name, String model, Double temperature, List<FallbackRef> fallbacks) {

    public ProviderRef {
      fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
    }

    /** 旧三参构造保留（既有构造点/测试兼容）：无备用声明委托空列表（023 纯增量）。 */
    public ProviderRef(String name, String model, Double temperature) {
      this(name, model, temperature, List.of());
    }

    /** 一个备用候选：已注册 Provider 名 + 该 Provider 下使用的模型名。 */
    public record FallbackRef(String name, String model) {}
  }

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

  /**
   * 执行环境声明（024）：frontmatter 可选段——backend 覆写全局执行后端档位，memory/cpus 覆写 docker 限额； 字段缺省 =
   * 继承全局。段本身缺省（sandbox 为 null）= 完全继承（绝大多数 Agent 的形态）。
   */
  public record Sandbox(String backend, String memory, String cpus) {}
}
