package io.oryxos.core.policy;

import io.oryxos.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 治理元数据读取（041 / #463）。
 *
 * <p>Agent / Skill / Knowledge 约定落在 {@code
 * .oryxos/{agents|skills|knowledge}/&lt;name&gt;/GOVERNANCE.yml}。 渠道不写侧车：从同一工作区根的 {@code
 * channels.yaml} 条目 {@code governance:} 块读取。写入渠道治理只走渠道 Admin → loader.save，本类不另写一份以免被覆盖丢掉。
 *
 * <p>缺文件或未知渠道返回 {@link AssetGovernance#empty()}——调用方不得把「没元数据」当成拒绝。侧车写入用原子改名。
 */
public final class AssetGovernanceStore {

  private static final Logger LOG = LoggerFactory.getLogger(AssetGovernanceStore.class);

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  /** 侧车文件名（常量：避免调用点散落字面量）。 */
  static final String FILE_NAME = "GOVERNANCE.yml";

  private static final String DIR_AGENTS = "agents";

  private static final String DIR_SKILLS = "skills";

  private static final String DIR_KNOWLEDGE = "knowledge";

  private static final String KEY_OWNER = "owner";

  private static final String KEY_VERSION = "version";

  private static final String KEY_VISIBILITY = "visibility";

  private static final String KEY_RISK = "riskLevel";

  private static final String KEY_HEALTH = "health";

  /** 渠道配置文件名（与运行时 {@code oryxosRoot/channels.yaml} 同一路径）。 */
  static final String CHANNELS_FILE = "channels.yaml";

  private static final String KEY_CHANNELS = "channels";

  private static final String KEY_ENTRY_NAME = "name";

  /** channels.yaml 条目上的治理块键。Loader 回写必须使用同一常量，避免两套字面量漂移。 */
  public static final String KEY_GOVERNANCE = "governance";

  private final Path workspaceRoot;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Path 是不可变路径值；规范化后只存根目录引用供后续 resolve。")
  public AssetGovernanceStore(Path workspaceRoot) {
    if (workspaceRoot == null) {
      throw new IllegalArgumentException("workspaceRoot 不能为空");
    }
    this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
  }

  /** 读取 Agent 侧车；缺文件或解析失败返回 empty（解析失败记 WARN，不把脏文件当成拒绝）。 */
  public AssetGovernance loadAgent(String name) {
    return load(path(DIR_AGENTS, name));
  }

  /** 读取 Skill 侧车。 */
  public AssetGovernance loadSkill(String name) {
    return load(path(DIR_SKILLS, name));
  }

  /** 读取知识库侧车。 */
  public AssetGovernance loadKnowledge(String name) {
    return load(path(DIR_KNOWLEDGE, name));
  }

  /** 按资源类型加载；未知类型返回 empty。 */
  public AssetGovernance load(String resourceType, String id) {
    if (resourceType == null) {
      return AssetGovernance.empty();
    }
    return switch (resourceType) {
      case ResourceRef.TYPE_AGENT -> loadAgent(id);
      case ResourceRef.TYPE_SKILL -> loadSkill(id);
      case ResourceRef.TYPE_KNOWLEDGE -> loadKnowledge(id);
      case ResourceRef.TYPE_CHANNEL -> loadChannel(id);
      default -> AssetGovernance.empty();
    };
  }

  /** 写入 Agent 侧车。 */
  public void saveAgent(String name, AssetGovernance governance) {
    save(path(DIR_AGENTS, name), governance);
  }

  /** 写入 Skill 侧车。 */
  public void saveSkill(String name, AssetGovernance governance) {
    save(path(DIR_SKILLS, name), governance);
  }

  /** 写入知识库侧车。 */
  public void saveKnowledge(String name, AssetGovernance governance) {
    save(path(DIR_KNOWLEDGE, name), governance);
  }

  /** 读取渠道治理块。缺文件、未知名称、块缺失或解析失败均返回 empty（不把脏文件当成拒绝，也不记录可能含凭证的原文）。 */
  public AssetGovernance loadChannel(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      return AssetGovernance.empty();
    }
    Path file = workspaceRoot.resolve(CHANNELS_FILE);
    if (!Files.isRegularFile(file)) {
      return AssetGovernance.empty();
    }
    try {
      String yaml = Files.readString(file);
      if (yaml.isBlank()) {
        return AssetGovernance.empty();
      }
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
      if (!(loaded instanceof Map<?, ?> root)) {
        return AssetGovernance.empty();
      }
      Object channels = root.get(KEY_CHANNELS);
      if (!(channels instanceof List<?> list)) {
        return AssetGovernance.empty();
      }
      for (Object item : list) {
        if (!(item instanceof Map<?, ?> entry)) {
          continue;
        }
        if (name.equals(text(entry.get(KEY_ENTRY_NAME)))) {
          return parseNode(entry.get(KEY_GOVERNANCE));
        }
      }
      return AssetGovernance.empty();
    } catch (IOException | YAMLException ex) {
      LOG.warn("读取 channels.yaml 治理块失败，按未设治理处理");
      return AssetGovernance.empty();
    }
  }

  private Path path(String dir, String name) {
    requireSafe(name);
    return workspaceRoot.resolve(dir).resolve(name).resolve(FILE_NAME);
  }

  private static void requireSafe(String name) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法资产名: " + name);
    }
  }

  private AssetGovernance load(Path file) {
    if (!Files.isRegularFile(file)) {
      return AssetGovernance.empty();
    }
    try {
      String text = Files.readString(file);
      if (text.isBlank()) {
        return AssetGovernance.empty();
      }
      Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
      if (!(loaded instanceof Map<?, ?> raw)) {
        LOG.warn("GOVERNANCE.yml 不是映射，按未设治理处理");
        return AssetGovernance.empty();
      }
      return fromMap(raw);
    } catch (IOException | YAMLException ex) {
      LOG.warn("读取 GOVERNANCE.yml 失败，按未设治理处理");
      return AssetGovernance.empty();
    }
  }

  /** 解析嵌套治理块。非映射、空映射或无有效字段返回 empty。未知键忽略——避免把凭证字段带进治理模型。 */
  public static AssetGovernance parseNode(Object node) {
    if (!(node instanceof Map<?, ?> raw) || raw.isEmpty()) {
      return AssetGovernance.empty();
    }
    return fromMap(raw);
  }

  /** 可回写的治理字段。未设返回空映射（调用方省略键）。只含已知字段，不含凭证。 */
  public static Map<String, String> toBlock(AssetGovernance governance) {
    Map<String, String> body = new LinkedHashMap<>();
    if (governance == null || !governance.isPresent()) {
      return body;
    }
    putText(body, KEY_OWNER, governance.owner());
    putText(body, KEY_VERSION, governance.version());
    if (governance.visibility() != null) {
      body.put(KEY_VISIBILITY, governance.visibility().name());
    }
    putText(body, KEY_RISK, governance.riskLevel());
    if (governance.health() != null) {
      body.put(KEY_HEALTH, governance.health().name());
    }
    return body;
  }

  private static void putText(Map<String, String> body, String key, String value) {
    if (value != null && !value.isBlank()) {
      body.put(key, value.strip());
    }
  }

  private static AssetGovernance fromMap(Map<?, ?> raw) {
    return new AssetGovernance(
        text(raw.get(KEY_OWNER)),
        text(raw.get(KEY_VERSION)),
        AssetGovernance.parseVisibility(text(raw.get(KEY_VISIBILITY))),
        text(raw.get(KEY_RISK)),
        AssetGovernance.parseHealth(text(raw.get(KEY_HEALTH))));
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String rendered = value.toString().strip();
    return rendered.isEmpty() ? null : rendered;
  }

  private void save(Path file, AssetGovernance governance) {
    if (governance == null) {
      throw new IllegalArgumentException("governance 不能为空");
    }
    AtomicFiles.writeString(file, render(governance));
  }

  /** 渲染侧车 YAML（块风格，字段名稳定）。与 channels.yaml 治理块共用 {@link #toBlock}。 */
  static String render(AssetGovernance governance) {
    Map<String, String> body = toBlock(governance);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    String dumped = new Yaml(options).dump(body);
    return dumped.endsWith("\n") ? dumped : dumped + "\n";
  }

  /** 变更摘要（进审计，不含凭据）。 */
  public static String summarize(AssetGovernance governance) {
    if (governance == null || !governance.isPresent()) {
      return "clear";
    }
    String visibility =
        governance.visibility() == null
            ? ""
            : governance.visibility().name().toLowerCase(Locale.ROOT);
    String health =
        governance.health() == null ? "" : governance.health().name().toLowerCase(Locale.ROOT);
    return "owner="
        + nullToEmpty(governance.owner())
        + " visibility="
        + visibility
        + " health="
        + health;
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.strip();
  }
}
