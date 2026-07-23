package io.oryxos.core.mcp;

import java.util.List;

/**
 * 内置 MCP 目录里的一条：业界公开 MCP server 的连接元信息模板。管理台"一键启用"照这个模板生成一份 {@link McpServerConfig}， 用户只需要填 {@link
 * #requiredEnv()} 里列的凭证，不用从零手填 command/url。
 *
 * <p>{@code commandTemplate}/{@code urlTemplate} 里的 {@code ${ENV}} 占位符与 {@link McpServerConfig} 的
 * env/headers 占位规则一致，一键启用时原样透传，实际取值仍在运行时由环境变量解析（凭证不落盘明文）。
 */
public record McpCatalogEntry(
    String id,
    String displayName,
    String description,
    String transport,
    String commandTemplate,
    String urlTemplate,
    List<String> requiredEnv,
    String docsHint) {

  public McpCatalogEntry {
    requiredEnv = requiredEnv == null ? List.of() : List.copyOf(requiredEnv);
  }
}
