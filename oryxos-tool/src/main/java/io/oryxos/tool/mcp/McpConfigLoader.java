package io.oryxos.tool.mcp;

import java.io.IOException;
import java.io.Reader;
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
import org.yaml.snakeyaml.Yaml;

/**
 * 读取 {@code .oryxos/mcp_servers.yaml}（顶层 servers: 列表；文件缺失 = 零 server，启动照常）。
 *
 * <p>env 值支持 {@code ${ENV}} 占位；环境变量缺失时保留原样并 WARN（16 节 ProfileLoader 同口径）。
 */
public class McpConfigLoader {

  private static final Logger LOG = LoggerFactory.getLogger(McpConfigLoader.class);

  private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_]+)}");

  private final Path configFile;

  public McpConfigLoader(Path configFile) {
    this.configFile = configFile;
  }

  @SuppressWarnings("unchecked")
  public List<McpServerConfig> load() {
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
                resolveEnv(entry.get("env"))));
      }
    }
    return configs;
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> resolveEnv(Object env) {
    if (!(env instanceof Map)) {
      return Map.of();
    }
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) env).entrySet()) {
      resolved.put(entry.getKey(), resolvePlaceholders(String.valueOf(entry.getValue())));
    }
    return resolved;
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
