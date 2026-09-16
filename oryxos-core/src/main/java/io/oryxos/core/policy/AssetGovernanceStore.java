package io.oryxos.core.policy;

import io.oryxos.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
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
 * GOVERNANCE.yml 侧车读写（041 / #463）：约定落在 {@code .oryxos/{agents|skills|knowledge}/&lt;name&gt;/}。
 *
 * <p>缺文件返回 {@link AssetGovernance#empty()}——调用方不得把「没元数据」当成拒绝。写入用原子改名，避免共享卷读到半文件。 渠道侧车本刀不写。
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
        LOG.warn("GOVERNANCE.yml 不是映射，按未设治理处理: {}", file.getFileName());
        return AssetGovernance.empty();
      }
      return fromMap(raw);
    } catch (IOException | YAMLException ex) {
      LOG.warn("读取 GOVERNANCE.yml 失败，按未设治理处理: {}", file.getFileName());
      return AssetGovernance.empty();
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

  /** 渲染侧车 YAML（块风格，字段名稳定）。 */
  static String render(AssetGovernance governance) {
    Map<String, String> body = new LinkedHashMap<>();
    if (governance.owner() != null && !governance.owner().isBlank()) {
      body.put(KEY_OWNER, governance.owner().strip());
    }
    if (governance.version() != null && !governance.version().isBlank()) {
      body.put(KEY_VERSION, governance.version().strip());
    }
    if (governance.visibility() != null) {
      body.put(KEY_VISIBILITY, governance.visibility().name());
    }
    if (governance.riskLevel() != null && !governance.riskLevel().isBlank()) {
      body.put(KEY_RISK, governance.riskLevel().strip());
    }
    if (governance.health() != null) {
      body.put(KEY_HEALTH, governance.health().name());
    }
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
