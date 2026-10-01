package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
        .thenReturn(new McpSchema.ListToolsResult(List.of(mcpTool("good_mcp_tool", "好工具")), null));
    return client;
  }

  private static McpSchema.Tool mcpTool(String name, String description) {
    return McpSchema.Tool.builder()
        .name(name)
        .description(description)
        .inputSchema(io.modelcontextprotocol.json.McpJsonDefaults.getMapper(), "{}")
        .build();
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
                List.of(mcpTool("tool_a", "a"), mcpTool("tool_b", "b")), null));
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
  @DisplayName("连接 tools/list 探测最多 60 秒，较短配置保持原值")
  void connectProbeTimeout_isBounded() {
    McpServerConfig shortTimeout =
        new McpServerConfig("short", "stdio", "echo", Map.of(), null, Map.of(), 15);
    McpServerConfig longTimeout =
        new McpServerConfig("long", "stdio", "echo", Map.of(), null, Map.of(), 300);

    assertEquals(Duration.ofSeconds(15), McpClientService.connectProbeTimeout(shortTimeout));
    assertEquals(Duration.ofSeconds(60), McpClientService.connectProbeTimeout(longTimeout));
  }

  @Test
  @DisplayName("tools/list 卡住时按连接探测上限返回并关闭客户端")
  void connectProbeTimeout_closesStalledClient() throws Exception {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenAnswer(
            invocation -> {
              Thread.sleep(Duration.ofSeconds(10));
              return new McpSchema.ListToolsResult(List.of(), null);
            });
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: stalled
                transport: stdio
                command: echo
                request_timeout: 1
            """);
    McpClientService service = new McpClientService(loader, config -> client);

    long started = System.nanoTime();
    service.connectAll(new ToolRegistry());
    long elapsed = System.nanoTime() - started;

    assertTrue(elapsed < Duration.ofSeconds(3).toNanos(), "连接探测应在 1 秒左右返回");
    assertTrue(service.status("stalled").error().contains("tools/list 探测超过 1 秒"));
    verify(client).closeGracefully();
  }

  @Test
  @DisplayName("listTools 中途撞名：已注册 MCP 工具回滚，客户端关闭")
  void listToolsFailure_unregistersPartialToolsAndClosesClient() throws IOException {
    OryxTool builtin = stubTool("http_get");
    ToolRegistry registry = new ToolRegistry();
    registry.register(builtin);

    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(
            new McpSchema.ListToolsResult(
                List.of(mcpTool("unique_mcp_tool", "ok"), mcpTool("http_get", "collide")), null));
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: leaky\n    transport: stdio\n    command: c\n");
    McpClientService service = new McpClientService(loader, config -> client);

    service.connectAll(registry);

    assertFalse(registry.contains("unique_mcp_tool"), "中途失败不得留下半截 MCP 工具");
    assertSame(builtin, registry.get("http_get").orElseThrow());
    assertTrue(registry.mcpToolOwners().isEmpty());
    verify(client).closeGracefully();

    McpServerStatus status = service.status("leaky");
    assertFalse(status.connected());
    assertTrue(status.toolNames().isEmpty());
    assertTrue(status.error() != null && status.error().contains("http_get"));
  }

  @Test
  @DisplayName("连接后进程死掉：status 报未连接、工具注销、错误可见")
  void statusReflectsAServerThatDiedAfterConnecting() throws IOException {
    // Nothing else notices: the entry stays in the map and its tools stay registered, so the admin
    // list and the capability catalogue keep advertising a server that cannot answer.
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools())
        .thenReturn(new McpSchema.ListToolsResult(List.of(mcpTool("gone_tool", "g")), null));
    // First probe succeeds (the connection is live), later ones fail (the process has died).
    when(client.ping()).thenReturn(null).thenThrow(new IllegalStateException("transport closed"));
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: gone\n    transport: stdio\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();
    // Zero cache: a default window would keep reporting the last good probe for its duration.
    McpClientService service = new McpClientService(loader, config -> client, Duration.ZERO);
    service.connectAll(registry);
    assertTrue(service.status("gone").connected(), "先要连上，否则这个测试没测到东西");

    McpServerStatus after = service.status("gone");

    assertFalse(after.connected());
    assertTrue(
        after.error() != null && after.error().contains("已断开"), String.valueOf(after.error()));
    assertTrue(after.toolNames().isEmpty());
    assertTrue(registry.mcpToolOwners().isEmpty(), "死掉的 server 不得继续占着工具");
    verify(client).closeGracefully();
  }

  @Test
  @DisplayName("closeAll 关掉每个连接并注销工具")
  void closeAllClosesEveryConnection() throws IOException {
    // Registered as the bean's destroy method: connections open for any command that builds a tool
    // registry, and a stdio server only exits once its client closes the transport.
    McpSyncClient alpha = mock(McpSyncClient.class);
    McpSyncClient beta = mock(McpSyncClient.class);
    when(alpha.listTools())
        .thenReturn(new McpSchema.ListToolsResult(List.of(mcpTool("alpha_tool", "a")), null));
    when(beta.listTools())
        .thenReturn(new McpSchema.ListToolsResult(List.of(mcpTool("beta_tool", "b")), null));
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: alpha
                transport: stdio
                command: a
              - name: beta
                transport: stdio
                command: b
            """);
    ToolRegistry registry = new ToolRegistry();
    McpClientService service =
        new McpClientService(loader, config -> "alpha".equals(config.name()) ? alpha : beta);
    service.connectAll(registry);
    assertTrue(registry.contains("alpha_tool") && registry.contains("beta_tool"));

    service.closeAll();

    verify(alpha).closeGracefully();
    verify(beta).closeGracefully();
    assertFalse(service.status("alpha").connected());
    assertFalse(service.status("beta").connected());
    assertTrue(registry.mcpToolOwners().isEmpty(), "关闭后不得留下无主的 MCP 工具");
  }

  @Test
  @DisplayName("initialize 失败也要关掉已构造的客户端")
  void initializeFailure_closesClient() throws IOException {
    McpSyncClient client = mock(McpSyncClient.class);
    doThrow(new IllegalStateException("handshake failed")).when(client).initialize();
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: dead\n    transport: stdio\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, config -> client).connectAll(registry);

    assertTrue(registry.all().isEmpty());
    verify(client).closeGracefully();
  }

  private static OryxTool stubTool(String name) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name;
      }

      @Override
      public String getInputSchema() {
        return "{}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return ToolResult.ok("ok");
      }
    };
  }

  @Test
  @DisplayName("unknown transport is skipped")
  void unsupportedTransportIsSkipped() throws IOException {
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: ws-server\n    transport: websocket\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, config -> goodClient()).connectAll(registry);

    assertTrue(registry.all().isEmpty());
  }

  @Test
  @DisplayName("sse and streamable transports connect via factory")
  void sseAndStreamableTransportsAreConnected() throws IOException {
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: sse-server
                transport: sse
                url: https://example.com/sse
              - name: streamable-server
                transport: streamable
                url: https://example.com/mcp
            """);
    Function<McpServerConfig, McpSyncClient> factory =
        config -> {
          McpSyncClient client = mock(McpSyncClient.class);
          when(client.listTools())
              .thenReturn(
                  new McpSchema.ListToolsResult(
                      List.of(mcpTool(config.name().replace('-', '_') + "_tool", "ok")), null));
          return client;
        };
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, factory).connectAll(registry);

    assertTrue(registry.contains("sse_server_tool"));
    assertTrue(registry.contains("streamable_server_tool"));
  }
}
