package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 31 节验收：MCP server 管理台 CRUD——增/改/删都是"落盘 + 立即生效"，凭证占位符不因编辑而被解析后写死。 */
class McpServerAdminServiceTest {

  @TempDir Path dir;

  private McpServerAdminService serviceWith(
      java.util.function.Function<McpServerConfig, io.modelcontextprotocol.client.McpSyncClient>
          factory) {
    McpConfigLoader loader = new McpConfigLoader(dir.resolve("mcp_servers.yaml"));
    McpClientService clientService = new McpClientService(loader, factory);
    return new McpServerAdminService(loader, clientService, new ToolRegistry());
  }

  private static io.modelcontextprotocol.client.McpSyncClient goodClient() {
    io.modelcontextprotocol.client.McpSyncClient client =
        org.mockito.Mockito.mock(io.modelcontextprotocol.client.McpSyncClient.class);
    org.mockito.Mockito.when(client.listTools())
        .thenReturn(
            new io.modelcontextprotocol.spec.McpSchema.ListToolsResult(
                List.of(new io.modelcontextprotocol.spec.McpSchema.Tool("demo_tool", "demo", "{}")),
                null));
    return client;
  }

  @Test
  @DisplayName("add: 落盘 + 立即连接")
  void add_persistsAndConnects() {
    McpServerAdminService service = serviceWith(c -> goodClient());

    service.add(new McpServerConfig("demo", "stdio", "echo hi", Map.of(), null, Map.of()));

    assertEquals(1, service.list().size());
    List<McpServerStatus> statuses = service.status();
    assertTrue(statuses.get(0).connected());
    assertTrue(statuses.get(0).toolNames().contains("demo_tool"));
  }

  @Test
  @DisplayName("add: 名字冲突拒绝")
  void add_duplicateName_rejected() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    service.add(new McpServerConfig("demo", "stdio", "echo hi", Map.of(), null, Map.of()));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.add(
                new McpServerConfig("demo", "stdio", "echo hi2", Map.of(), null, Map.of())));
  }

  @Test
  @DisplayName("remove: 断开连接 + 从配置移除，幂等")
  void remove_disconnectsAndPersists() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    service.add(new McpServerConfig("demo", "stdio", "echo hi", Map.of(), null, Map.of()));

    service.remove("demo");

    assertTrue(service.list().isEmpty());
    assertFalse(service.status().stream().anyMatch(s -> s.name().equals("demo") && s.connected()));
    assertTrue(
        () -> {
          service.remove("demo"); // 幂等：不存在也不抛
          return true;
        });
  }

  @Test
  @DisplayName("update: raw 配置里的 ${ENV} 占位符原样落盘，不因编辑被解析成明文")
  void update_keepsPlaceholderLiteralOnDisk() throws IOException {
    McpServerAdminService service = serviceWith(c -> goodClient());
    service.add(
        new McpServerConfig(
            "demo", "stdio", "echo hi", Map.of("TOKEN", "${MY_SECRET_ENV}"), null, Map.of()));

    service.update(
        "demo",
        new McpServerConfig(
            "demo", "stdio", "echo hi --v2", Map.of("TOKEN", "${MY_SECRET_ENV}"), null, Map.of()));

    String yaml = Files.readString(dir.resolve("mcp_servers.yaml"));
    assertTrue(yaml.contains("${MY_SECRET_ENV}"), "占位符必须原样落盘，不能被解析成真实凭证明文");
  }

  @Test
  @DisplayName("catalog: 返回内置目录，非空")
  void catalog_returnsBuiltInEntries() {
    McpServerAdminService service = serviceWith(c -> goodClient());
    assertFalse(service.catalog().isEmpty());
  }
}
