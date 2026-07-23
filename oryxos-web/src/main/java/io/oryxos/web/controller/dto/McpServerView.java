package io.oryxos.web.controller.dto;

import io.oryxos.core.mcp.McpServerConfig;
import java.util.Map;

/** MCP server 视图（列表/详情返回）：env/headers 里的 {@code ${ENV}} 占位符原样回显，不解析成明文。 */
public record McpServerView(
    String name,
    String transport,
    String command,
    Map<String, String> env,
    String url,
    Map<String, String> headers) {

  public McpServerView {
    env = env == null ? Map.of() : Map.copyOf(env);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  public static McpServerView from(McpServerConfig c) {
    return new McpServerView(c.name(), c.transport(), c.command(), c.env(), c.url(), c.headers());
  }

  public McpServerConfig toConfig() {
    return new McpServerConfig(name, transport, command, env, url, headers);
  }
}
