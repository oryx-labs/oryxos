package io.oryxos.web.controller.dto;

import java.util.Map;

/** 从内置目录一键启用一个 MCP server：{@code credentials} 按目录条目 requiredEnv 里列的 key 填凭证值。 */
public record EnableMcpCatalogRequest(String name, Map<String, String> credentials) {

  public EnableMcpCatalogRequest {
    credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
  }
}
