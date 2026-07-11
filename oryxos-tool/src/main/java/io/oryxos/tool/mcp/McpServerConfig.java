package io.oryxos.tool.mcp;

import java.util.Map;

/** 一个外部 MCP server 的连接配置（.oryxos/mcp_servers.yaml 条目）。 */
public record McpServerConfig(
    String name, String transport, String command, Map<String, String> env) {

  public McpServerConfig {
    env = env == null ? Map.of() : Map.copyOf(env);
  }
}
