package io.oryxos.tool.mcp;

import io.oryxos.core.mcp.McpCatalog;
import io.oryxos.core.mcp.McpCatalogEntry;
import io.oryxos.core.mcp.McpServerAdmin;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.tool.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link McpServerAdmin} 的落地实现（31 节）：{@link McpConfigLoader} 管持久化（raw 口径，凭证占位符不落盘明文），{@link
 * McpClientService} 管连接生命周期，{@link ToolRegistry} 是它们共同作用的对象。增/改/删都是"落盘 + 立即生效"，无需重启 OryxOS。
 */
public class McpServerAdminService implements McpServerAdmin {

  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");

  private final McpConfigLoader configLoader;
  private final McpClientService clientService;
  private final ToolRegistry toolRegistry;

  public McpServerAdminService(
      McpConfigLoader configLoader, McpClientService clientService, ToolRegistry toolRegistry) {
    this.configLoader = configLoader;
    this.clientService = clientService;
    this.toolRegistry = toolRegistry;
  }

  @Override
  public List<McpServerConfig> list() {
    return configLoader.loadRaw();
  }

  @Override
  public synchronized McpServerConfig add(McpServerConfig config) {
    validate(config);
    List<McpServerConfig> current = new ArrayList<>(configLoader.loadRaw());
    if (current.stream().anyMatch(c -> c.name().equals(config.name()))) {
      throw new IllegalArgumentException("MCP server 已存在: " + config.name());
    }
    current.add(config);
    configLoader.save(current);
    clientService.connect(configLoader.resolve(config), toolRegistry);
    return config;
  }

  @Override
  public synchronized McpServerConfig update(String name, McpServerConfig config) {
    if (!name.equals(config.name())) {
      throw new IllegalArgumentException("不支持改名: " + name + " -> " + config.name());
    }
    validate(config);
    List<McpServerConfig> current = new ArrayList<>(configLoader.loadRaw());
    int idx = indexOf(current, name);
    if (idx < 0) {
      throw new IllegalArgumentException("MCP server 不存在: " + name);
    }
    clientService.disconnect(name, toolRegistry); // 断旧连接，避免新旧工具并存打架
    current.set(idx, config);
    configLoader.save(current);
    clientService.connect(configLoader.resolve(config), toolRegistry);
    return config;
  }

  @Override
  public synchronized void remove(String name) {
    List<McpServerConfig> current = new ArrayList<>(configLoader.loadRaw());
    int idx = indexOf(current, name);
    clientService.disconnect(name, toolRegistry);
    if (idx >= 0) {
      current.remove(idx);
      configLoader.save(current);
    }
  }

  @Override
  public List<McpServerStatus> status() {
    return configLoader.loadRaw().stream().map(c -> clientService.status(c.name())).toList();
  }

  @Override
  public List<McpCatalogEntry> catalog() {
    return McpCatalog.all();
  }

  private static int indexOf(List<McpServerConfig> configs, String name) {
    for (int i = 0; i < configs.size(); i++) {
      if (configs.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  private static void validate(McpServerConfig config) {
    Objects.requireNonNull(config, "MCP server 配置为空");
    if (config.name() == null || !SAFE_NAME.matcher(config.name()).matches()) {
      throw new IllegalArgumentException("非法 MCP server 名（只允许字母/数字/下划线/连字符）: " + config.name());
    }
    String transport = config.transport();
    if (McpServerConfig.TRANSPORT_STDIO.equals(transport)) {
      if (config.command() == null || config.command().isBlank()) {
        throw new IllegalArgumentException("stdio server 缺少 command: " + config.name());
      }
    } else if (McpServerConfig.TRANSPORT_HTTP.equals(transport)) {
      if (config.url() == null || config.url().isBlank()) {
        throw new IllegalArgumentException("http server 缺少 url: " + config.name());
      }
    } else {
      throw new IllegalArgumentException("未知 transport（仅支持 stdio/http）: " + config.transport());
    }
  }
}
