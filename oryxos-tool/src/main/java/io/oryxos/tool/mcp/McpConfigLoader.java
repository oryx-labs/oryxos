package io.oryxos.tool.mcp;

import io.oryxos.core.mcp.McpServerConfig;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 读写 {@code .oryxos/mcp_servers.yaml}（顶层 servers: 列表；文件缺失 = 零 server，启动照常）。
 *
 * <p>env/headers 值支持 {@code ${ENV}} 占位；环境变量缺失时保留原样并 WARN（16 节 ProfileLoader 同口径）。
 *
 * <p><b>raw 与 resolved 两套读法，不能混用</b>：{@link #load} 解析占位符拿真实凭证，只给要发起真实连接的 {@code McpClientService}
 * 用；{@link #loadRaw} 保留字面量 {@code ${VAR}} 不解析，给管理台 CRUD（列表展示 / 改配置再 {@link
 * #save}）用——否则改一次配置就会把解析出的明文 token 写回磁盘，凭证泄露进文件（宪法：敏感配置走环境变量，不落盘明文）。
 */
public class McpConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(McpConfigLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

  private final Path configFile;

  public McpConfigLoader(Path configFile) {
    this.configFile = configFile;
  }

  /** 解析占位符后的配置——唯一给真实连接用的读法，凭证在此落地成真实值（仅存活在内存里）。 */
  public List<McpServerConfig> load() {
    List<McpServerConfig> resolved = new ArrayList<>();
    for (McpServerConfig c : loadRaw()) {
      resolved.add(resolve(c));
    }
    return resolved;
  }

  /** 对单份（管理台增/改时手头这一份）原始配置解析占位符，供 {@code McpServerAdminService} 增/改后立即连接用。 */
  public McpServerConfig resolve(McpServerConfig raw) {
    return new McpServerConfig(
        raw.name(),
        raw.transport(),
        raw.command(),
        resolvePlaceholderMap(raw.env()),
        raw.url(),
        resolvePlaceholderMap(raw.headers()));
  }

  /** 不解析占位符的原始配置——管理台 CRUD 专用（展示 + 改后回写），保证 {@code ${VAR}} 字面量原样落盘。 */
  @SuppressWarnings("unchecked")
  public List<McpServerConfig> loadRaw() {
    if (!Files.isRegularFile(configFile)) {
      return List.of();
    }
    Map<String, Object> root;
    try (Reader reader = Files.newBufferedReader(configFile)) {
      root = new Yaml().load(reader);
    } catch (IOException | RuntimeException e) {
      LOG.warn("mcp_servers.yaml 解析失败，按零 server 处理: {}", sanitize(e.getMessage()));
      return List.of();
    }
    Object servers = root == null ? null : root.get("servers");
    if (!(servers instanceof List)) {
      return List.of();
    }
    List<McpServerConfig> configs = new ArrayList<>();
    for (Object item : (List<Object>) servers) {
      if (item instanceof Map) {
        Map<String, Object> entry = (Map<String, Object>) item;
        configs.add(
            new McpServerConfig(
                asString(entry.get("name")),
                asString(entry.get("transport")),
                asString(entry.get("command")),
                asStringMap(entry.get("env")),
                asString(entry.get("url")),
                asStringMap(entry.get("headers"))));
      }
    }
    return configs;
  }

  /** 整份列表覆写回 {@code mcp_servers.yaml}（管理台增/改/删都落这一份文件）；传入的必须是 {@link #loadRaw} 口径的未解析配置。 */
  public void save(List<McpServerConfig> configs) {
    List<Map<String, Object>> servers = new ArrayList<>();
    for (McpServerConfig c : configs) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("name", c.name());
      entry.put("transport", c.transport());
      if (c.command() != null) {
        entry.put("command", c.command());
      }
      if (c.url() != null) {
        entry.put("url", c.url());
      }
      if (!c.env().isEmpty()) {
        entry.put("env", c.env());
      }
      if (!c.headers().isEmpty()) {
        entry.put("headers", c.headers());
      }
      servers.add(entry);
    }
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("servers", servers);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    String yaml = new Yaml(options).dump(root);
    try {
      Path parent = configFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
        restrictToOwner(parent);
      }
      Files.writeString(configFile, yaml);
      restrictToOwner(configFile); // env/headers 里可能落了真实凭证（占位符解析不了时）——只给属主可读写
    } catch (IOException e) {
      throw new UncheckedIOException("写入 mcp_servers.yaml 失败", e);
    }
  }

  private static final String POSIX_VIEW = "posix";

  /** 收紧到属主可读写（POSIX：rwx------ 目录 / rw------- 文件）；非 POSIX 文件系统上静默跳过，不影响功能。 */
  private static void restrictToOwner(Path path) throws IOException {
    if (!path.getFileSystem().supportedFileAttributeViews().contains(POSIX_VIEW)) {
      return;
    }
    boolean isDir = Files.isDirectory(path);
    Files.setPosixFilePermissions(
        path,
        java.nio.file.attribute.PosixFilePermissions.fromString(isDir ? "rwx------" : "rw-------"));
  }

  private Map<String, String> resolvePlaceholderMap(Map<String, String> value) {
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : value.entrySet()) {
      resolved.put(entry.getKey(), resolvePlaceholders(entry.getValue()));
    }
    return resolved;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, String> asStringMap(Object value) {
    if (!(value instanceof Map)) {
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
      out.put(entry.getKey(), String.valueOf(entry.getValue()));
    }
    return out;
  }

  private String resolvePlaceholders(String text) {
    Matcher matcher = ENV_PLACEHOLDER.matcher(text);
    StringBuilder sb = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      if (value == null) {
        LOG.warn("环境变量未设置，占位符保留原样: {}", sanitize(matcher.group(1)));
        value = matcher.group(0);
      }
      matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
