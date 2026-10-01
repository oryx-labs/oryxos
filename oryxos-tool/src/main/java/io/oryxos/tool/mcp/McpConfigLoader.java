package io.oryxos.tool.mcp;

import io.oryxos.core.mcp.McpServerConfig;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>结构非法（YAML 解析失败 / 顶层 servers 非列表 / 条目非对象 / env·headers 非映射）抛 {@link IllegalArgumentException}
 * 点名报错，不静默按零 server / 空 map 处理——与 {@code ChannelConfigLoader} 同口径。
 */
public class McpConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(McpConfigLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");
  private static final String FIELD_REQUEST_TIMEOUT = "request_timeout";

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
        resolvePlaceholderMap(raw.headers()),
        raw.requestTimeoutSeconds());
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
      throw new IllegalArgumentException("mcp_servers.yaml 解析失败: " + sanitize(e.getMessage()), e);
    }
    Object servers = root == null ? null : root.get("servers");
    if (servers == null) {
      return List.of();
    }
    if (!(servers instanceof List)) {
      throw new IllegalArgumentException("mcp_servers.yaml 顶层 servers 必须是列表");
    }
    List<McpServerConfig> configs = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object item : (List<Object>) servers) {
      if (!(item instanceof Map)) {
        throw new IllegalArgumentException(
            "mcp_servers.yaml 存在非对象条目: " + sanitize(String.valueOf(item)));
      }
      Map<String, Object> entry = (Map<String, Object>) item;
      McpServerConfig config =
          new McpServerConfig(
              asString(entry.get("name")),
              asString(entry.get("transport")),
              asString(entry.get("command")),
              asStringMap(entry.get("env"), "env"),
              asString(entry.get("url")),
              asStringMap(entry.get("headers"), "headers"),
              asRequestTimeoutSeconds(entry.get(FIELD_REQUEST_TIMEOUT)));
      // The name is the connection's identity everywhere downstream: McpClientService keys its
      // clients by it, and the admin API refuses duplicates. Accepting one here silently replaced
      // the earlier connection without closing it, and orphaned its tools.
      if (!seen.add(config.name())) {
        throw new IllegalArgumentException("mcp_servers.yaml server 名重复: " + config.name());
      }
      configs.add(config);
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
      if (c.requestTimeoutSeconds() != McpServerConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS) {
        entry.put(FIELD_REQUEST_TIMEOUT, c.requestTimeoutSeconds());
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
        // Only tighten a directory this call creates. The parent is normally the workspace root,
        // which other uids may share (shared-posix), and narrowing it to 0700 would cut them off
        // from agents, knowledge and output — a change that never gets undone.
        boolean created = !Files.exists(parent);
        Files.createDirectories(parent);
        if (created) {
          restrictToOwner(parent);
        }
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

  /** {@code env}/{@code headers}：缺省或 null → 空 map；显式写成标量/列表则 fail-loud，避免凭证/请求头静默丢失。 */
  @SuppressWarnings("unchecked")
  private static Map<String, String> asStringMap(Object value, String field) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map)) {
      throw new IllegalArgumentException(
          "mcp_servers.yaml 的 " + field + " 必须是映射: " + sanitize(String.valueOf(value)));
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }
      out.put(entry.getKey(), asString(entry.getValue()));
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
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      return text;
    }
    // YAML 1.1：裸 yes/on/null 会变成 Boolean/null；String.valueOf(true)→"true" 会静默改名（对齐 #198）
    throw new IllegalArgumentException(
        "期望 YAML 字符串（yes/on/null 等请加引号），实际是 " + value.getClass().getSimpleName() + ": " + value);
  }

  private static int asRequestTimeoutSeconds(Object value) {
    if (value == null) {
      return McpServerConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS;
    }
    if (!(value instanceof Integer) && !(value instanceof Long)) {
      throw new IllegalArgumentException(
          "mcp_servers.yaml 的 "
              + FIELD_REQUEST_TIMEOUT
              + " 必须是整数秒数: "
              + sanitize(String.valueOf(value)));
    }
    long seconds = ((Number) value).longValue();
    if (seconds < Integer.MIN_VALUE || seconds > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "mcp_servers.yaml 的 "
              + FIELD_REQUEST_TIMEOUT
              + " 超出整数范围: "
              + sanitize(String.valueOf(value)));
    }
    return (int) seconds;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
