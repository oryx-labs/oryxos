package io.oryxos.web.controller.dto;

import io.oryxos.core.mcp.McpCatalogEntry;
import java.util.List;

/** 内置 MCP 目录一条的视图。 */
public record McpCatalogView(
    String id,
    String displayName,
    String description,
    String transport,
    String commandTemplate,
    String urlTemplate,
    List<String> requiredEnv,
    String docsHint) {

  public McpCatalogView {
    requiredEnv = requiredEnv == null ? List.of() : List.copyOf(requiredEnv);
  }

  public static McpCatalogView from(McpCatalogEntry e) {
    return new McpCatalogView(
        e.id(),
        e.displayName(),
        e.description(),
        e.transport(),
        e.commandTemplate(),
        e.urlTemplate(),
        e.requiredEnv(),
        e.docsHint());
  }
}
