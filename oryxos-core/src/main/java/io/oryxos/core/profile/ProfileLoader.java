package io.oryxos.core.profile;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/**
 * 启动时扫描 {@code .oryxos/profiles/} 下全部 YAML，解析并校验为 {@link Profile}。
 *
 * <p>坏文件记 ERROR 跳过、不阻断其余加载（SC-007）。provider 名合法性依据构造注入的 knownProviders——oryxos-core 不反向依赖 provider
 * 模块，名单由装配方提供。
 */
public class ProfileLoader {

  private static final Logger LOG = LoggerFactory.getLogger(ProfileLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

  private final Path profilesDir;
  private final Set<String> knownProviders;
  private final UnaryOperator<String> envLookup;

  public ProfileLoader(Path profilesDir, Set<String> knownProviders) {
    this(profilesDir, knownProviders, System::getenv);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "31 节动态 provider：knownProviders 可为注册表实时视图，故存引用而非 Set.copyOf 拍照——运行时新增的 provider 才能立即对派生校验可见")
  public ProfileLoader(
      Path profilesDir, Set<String> knownProviders, UnaryOperator<String> envLookup) {
    this.profilesDir = profilesDir;
    this.knownProviders = knownProviders;
    this.envLookup = envLookup;
  }

  /** 扫描目录并返回加载成功的 Profile 索引；单文件失败只记日志。 */
  public ProfileRegistry loadAll() {
    Map<String, Profile> loaded = new LinkedHashMap<>();
    if (!Files.isDirectory(profilesDir)) {
      LOG.warn("Profile 目录不存在，跳过加载: {}", sanitize(profilesDir.toString()));
      return new ProfileRegistry(loaded);
    }
    try (Stream<Path> files = Files.list(profilesDir)) {
      files
          .filter(ProfileLoader::isYamlFile)
          .sorted()
          .forEach(
              file -> {
                try {
                  Profile profile = parse(file);
                  loaded.put(profile.name(), profile);
                } catch (RuntimeException | IOException e) {
                  // 坏文件不阻断启动，但必须留下可定位的痕迹
                  LOG.error(
                      "跳过损坏的 Profile 文件 {}: {}",
                      sanitize(String.valueOf(file.getFileName())),
                      sanitize(e.getMessage()));
                }
              });
    } catch (IOException e) {
      LOG.error("扫描 Profile 目录失败: {}", sanitize(e.getMessage()));
    }
    return new ProfileRegistry(loaded);
  }

  /** 解析单个文件并校验；失败抛 {@link ProfileValidationException}（供测试直接断言报错文案）。 */
  Profile parse(Path file) throws IOException {
    Map<String, Object> root;
    try (Reader reader = Files.newBufferedReader(file)) {
      root = new Yaml().load(reader);
    }
    if (root == null) {
      throw new ProfileValidationException("Profile 文件为空: " + file.getFileName());
    }
    return fromMap(root, String.valueOf(file.getFileName()));
  }

  /**
   * Map → Profile 的解析 + 全字段校验入口，供 {@code AgentLoader.deriveProfile} 复用其 AGENT.md frontmatter——
   * 保证"扫目录派生"与"启动/运行时"两条来源走同一套校验、同一异常同一消息（FR-006）。 {@code source} 是报错定位标签（文件名或 Agent 目录名）。
   */
  public Profile fromMap(Map<String, Object> map, String source) {
    if (map == null) {
      throw new ProfileValidationException("Profile 内容为空: " + source);
    }
    Object resolved = resolveEnvPlaceholders(map);
    @SuppressWarnings("unchecked")
    Map<String, Object> m = (Map<String, Object>) resolved;
    return toProfile(m, source);
  }

  private Profile toProfile(Map<String, Object> map, String source) {
    String name = asString(map.get("name"));
    if (name == null || name.isBlank()) {
      throw new ProfileValidationException("Profile 缺少 name 字段: " + source);
    }
    Profile.ProviderRef provider = toProviderRef(asMap(map.get("provider")), name);
    return new Profile(
        name,
        asString(map.get("description")),
        toIdentity(asMap(map.get("identity"))),
        provider,
        asStringList(map.get("tools")),
        asStringList(map.get("mcp_servers")),
        asStringList(map.get("channels")),
        toNotifyChannels(asList(map.get("notify_channels"))),
        toSchedules(asList(map.get("schedules"))),
        asStringList(map.get("bootstrap")),
        asStringList(map.get("skills")),
        toSettings(asMap(map.get("settings"))));
  }

  private Profile.ProviderRef toProviderRef(Map<String, Object> map, String profileName) {
    if (map == null) {
      throw new ProfileValidationException("Profile " + profileName + " 缺少 provider 段");
    }
    String providerName = asString(map.get("name"));
    String model = asString(map.get("model"));
    if (providerName == null || providerName.isBlank()) {
      throw new ProfileValidationException("Profile " + profileName + " 的 provider.name 为空");
    }
    if (model == null || model.isBlank()) {
      throw new ProfileValidationException("Profile " + profileName + " 的 provider.model 为空");
    }
    if (!knownProviders.contains(providerName)) {
      throw new ProfileValidationException(
          "Profile "
              + profileName
              + " 引用了未知的 provider: "
              + providerName
              + "（可用: "
              + knownProviders
              + "）");
    }
    return new Profile.ProviderRef(providerName, model, asDouble(map.get("temperature")));
  }

  private static Profile.Identity toIdentity(Map<String, Object> map) {
    if (map == null) {
      return null;
    }
    return new Profile.Identity(asString(map.get("agent_name")), asString(map.get("prompt")));
  }

  private static List<Profile.NotifyChannel> toNotifyChannels(List<Object> list) {
    if (list == null) {
      return List.of();
    }
    List<Profile.NotifyChannel> channels = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> entry = asMap(item);
      if (entry == null) {
        continue;
      }
      String type = asString(entry.get("type"));
      // type 之外的键都是渠道特定配置（如 webhook 的 url）
      Map<String, String> config = new LinkedHashMap<>();
      for (Map.Entry<String, Object> kv : entry.entrySet()) {
        if (!"type".equals(kv.getKey()) && kv.getValue() != null) {
          config.put(kv.getKey(), String.valueOf(kv.getValue()));
        }
      }
      channels.add(new Profile.NotifyChannel(type, config));
    }
    return channels;
  }

  private static List<Profile.ScheduleConfig> toSchedules(List<Object> list) {
    if (list == null) {
      return List.of();
    }
    List<Profile.ScheduleConfig> schedules = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> entry = asMap(item);
      if (entry == null) {
        continue;
      }
      schedules.add(
          new Profile.ScheduleConfig(
              asString(entry.get("id")),
              asString(entry.get("cron")),
              asString(entry.get("zone")),
              asString(entry.get("message"))));
    }
    return schedules;
  }

  private static Profile.Settings toSettings(Map<String, Object> map) {
    if (map == null) {
      return Profile.Settings.defaults();
    }
    Profile.Settings defaults = Profile.Settings.defaults();
    return new Profile.Settings(
        asInt(map.get("max_iterations"), defaults.maxIterations()),
        asInt(map.get("max_history_turns"), defaults.maxHistoryTurns()));
  }

  /** 递归解析 ${ENV} 占位；环境变量缺失时保留原样并 WARN（凭证必填校验属全局层职责）。 */
  private Object resolveEnvPlaceholders(Object node) {
    if (node instanceof String text) {
      return resolveEnvInString(text);
    }
    if (node instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        out.put(String.valueOf(entry.getKey()), resolveEnvPlaceholders(entry.getValue()));
      }
      return out;
    }
    if (node instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object item : list) {
        out.add(resolveEnvPlaceholders(item));
      }
      return out;
    }
    return node;
  }

  private String resolveEnvInString(String text) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = envLookup.apply(key);
      if (value == null) {
        LOG.warn("环境变量未设置，占位符保留原样: {}", sanitize(key));
        value = matcher.group(0);
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private static boolean isYamlFile(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      return false;
    }
    String name = fileName.toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Double asDouble(Object value) {
    return value instanceof Number number ? number.doubleValue() : null;
  }

  private static int asInt(Object value, int defaultValue) {
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> asMap(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : null;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> asList(Object value) {
    return value instanceof List ? (List<Object>) value : null;
  }

  private static List<String> asStringList(Object value) {
    List<Object> list = asList(value);
    if (list == null) {
      return List.of();
    }
    return list.stream().map(String::valueOf).toList();
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
