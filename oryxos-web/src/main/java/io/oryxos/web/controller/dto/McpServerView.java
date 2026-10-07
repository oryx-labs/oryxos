package io.oryxos.web.controller.dto;

import io.oryxos.core.mcp.McpServerConfig;
import java.util.Map;

/**
 * MCP server 视图（列表/详情返回）：env/headers 里的 {@code ${ENV}} 占位符原样回显不解析；字面量凭证值只回显掩码——
 * 键名认不出凭证时值本身就是判据（{@code DATABASE_URI} / {@code X-Auth} / {@code SSH_PRIVATE_KEY}
 * 同样算），凭证明文永不回显（FR-012 口径）。
 */
public record McpServerView(
    String name,
    String transport,
    String command,
    Map<String, String> env,
    String url,
    Map<String, String> headers,
    Integer requestTimeoutSeconds) {

  public McpServerView {
    env = env == null ? Map.of() : Map.copyOf(env);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  public static McpServerView from(McpServerConfig c) {
    return new McpServerView(
        c.name(),
        c.transport(),
        c.command(),
        CredentialMasks.maskCredentialValues(c.env()),
        c.url(),
        CredentialMasks.maskCredentialValues(c.headers()),
        c.requestTimeoutSeconds());
  }

  public McpServerConfig toConfig() {
    int timeout =
        requestTimeoutSeconds == null
            ? McpServerConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS
            : requestTimeoutSeconds;
    return new McpServerConfig(name, transport, command, env, url, headers, timeout);
  }
}
