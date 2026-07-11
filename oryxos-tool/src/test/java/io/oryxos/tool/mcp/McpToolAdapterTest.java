package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 课件《第20节》验收 harness：McpToolAdapterTest——注册映射、参数原样转发、结果包装。 */
class McpToolAdapterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private McpSyncClient client;
  private McpToolAdapter adapter;

  @BeforeEach
  void setUp() {
    client = mock(McpSyncClient.class);
    adapter =
        new McpToolAdapter(
            client, new McpSchema.Tool("github_search", "搜 GitHub", "{\"type\":\"object\"}"));
  }

  @Test
  @DisplayName("三要素直接映射 tools/list 返回")
  void contractMapsFromMcpToolSpec() {
    assertEquals("github_search", adapter.getName());
    assertEquals("搜 GitHub", adapter.getDescription());
    assertTrue(adapter.getInputSchema().contains("object"));
  }

  @Test
  @DisplayName("execute 原样转发参数并包装成功结果")
  void executeForwardsArgsAndWrapsResult() throws Exception {
    when(client.callTool(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("found 3 repos")), false));

    ToolResult result = adapter.execute(MAPPER.readTree("{\"query\":\"oryxos\"}"));

    ArgumentCaptor<McpSchema.CallToolRequest> captor =
        ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
    verify(client).callTool(captor.capture());
    assertEquals("github_search", captor.getValue().name());
    assertEquals(Map.of("query", "oryxos"), captor.getValue().arguments()); // 参数原样
    assertTrue(result.success());
    assertEquals("found 3 repos", result.content());
  }

  @Test
  @DisplayName("MCP调用失败_包装为可重试的失败结果")
  void mcpErrorWrapsAsRetryableFailure() throws Exception {
    when(client.callTool(org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("rate limited")), true));

    ToolResult result = adapter.execute(MAPPER.readTree("{}"));

    assertFalse(result.success());
    assertTrue(result.retryable(), "网络/限流类失败值得循环再试一次");
    assertTrue(result.errorMessage().contains("rate limited"));
  }
}
