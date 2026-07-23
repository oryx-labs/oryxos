package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第20节》验收 harness：McpClientServiceTest——失联隔离是最值钱的那条。 */
class McpClientServiceTest {

  @TempDir Path dir;

  private McpConfigLoader loaderWith(String yaml) throws IOException {
    Path file = dir.resolve("mcp_servers.yaml");
    Files.writeString(file, yaml);
    return new McpConfigLoader(file);
  }

  private static McpSyncClient goodClient() {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(new McpSchema.Tool("good_mcp_tool", "好工具", "{}")), null));
    return client;
  }

  @Test
  @DisplayName("某个MCP_server失联_不能拖垮启动和其他工具")
  void oneMcpServerDown_doesNotBreakStartupOrOtherTools() throws IOException {
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: good-server
                transport: stdio
                command: good-cmd
              - name: bad-server
                transport: stdio
                command: bad-cmd
            """);
    Function<McpServerConfig, McpSyncClient> factory =
        config -> {
          if ("bad-server".equals(config.name())) {
            throw new IllegalStateException("Connection refused"); // 课件 ConnectException 语义
          }
          return goodClient();
        };
    ToolRegistry registry = new ToolRegistry();

    assertDoesNotThrow(
        () -> new McpClientService(loader, factory).connectAll(registry)); // 外部依赖的可用性不是自己的可用性

    assertTrue(registry.contains("good_mcp_tool")); // 好的 server 照常注册
    assertFalse(registry.contains("bad_mcp_tool"));
  }

  @Test
  @DisplayName("listTools 的每个工具都被包装注册")
  void allListedToolsAreRegistered() throws IOException {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(
                    new McpSchema.Tool("tool_a", "a", "{}"),
                    new McpSchema.Tool("tool_b", "b", "{}")),
                null));
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: s\n    transport: stdio\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, config -> client).connectAll(registry);

    assertTrue(registry.contains("tool_a"));
    assertTrue(registry.contains("tool_b"));
  }

  @Test
  @DisplayName("配置解析：command 拆分、env 占位、缺文件零 server")
  void configLoaderParsesEntriesAndHandlesMissingFile() throws IOException {
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: github-mcp
                transport: stdio
                command: npx -y server-github
                env:
                  TOKEN: ${ORYX_TEST_UNSET_ENV}
            """);

    List<McpServerConfig> configs = loader.load();

    assertTrue(configs.get(0).command().startsWith("npx"));
    assertTrue(configs.get(0).env().get("TOKEN").contains("${ORYX_TEST_UNSET_ENV}"), "缺失占位保留原样");
    assertTrue(new McpConfigLoader(dir.resolve("nope.yaml")).load().isEmpty());
  }

  @Test
  @DisplayName("transport 非 stdio 跳过不注册")
  void unsupportedTransportIsSkipped() throws IOException {
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: sse-server\n    transport: sse\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, config -> goodClient()).connectAll(registry);

    assertTrue(registry.all().isEmpty());
  }
}
