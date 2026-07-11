package io.oryxos.tool.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把一个 MCP 工具适配成 {@link OryxTool}：注册时三要素直接映射 tools/list 返回， 执行时经 MCP 协议原样转发、结果包成 ToolResult（TechSol
 * §6.4）。
 *
 * <p>调用失败标记可重试——网络抖动类失败值得 ReAct 循环再试一次。
 */
public class McpToolAdapter implements OryxTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McpSyncClient client;
  private final McpSchema.Tool tool;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "适配器必须持有 MCP 客户端连接引用（协议转发的载体）；连接生命周期由 McpClientService 管理")
  public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool) {
    this.client = client;
    this.tool = tool;
  }

  @Override
  public String getName() {
    return tool.name();
  }

  @Override
  public String getDescription() {
    return tool.description();
  }

  @Override
  public String getInputSchema() {
    try {
      return MAPPER.writeValueAsString(tool.inputSchema());
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("MCP 工具 schema 序列化失败: " + tool.name(), e);
    }
  }

  @Override
  public ToolResult execute(JsonNode input) {
    Map<String, Object> args =
        input == null
            ? Map.of()
            : MAPPER.convertValue(input, new TypeReference<Map<String, Object>>() {});
    McpSchema.CallToolResult result =
        client.callTool(new McpSchema.CallToolRequest(tool.name(), args));
    String content = renderContent(result);
    if (Boolean.TRUE.equals(result.isError())) {
      return ToolResult.error("MCP 调用失败: " + content, true); // 可重试
    }
    return ToolResult.ok(content);
  }

  private static String renderContent(McpSchema.CallToolResult result) {
    if (result.content() == null) {
      return "";
    }
    return result.content().stream()
        .map(c -> c instanceof McpSchema.TextContent text ? text.text() : String.valueOf(c))
        .collect(Collectors.joining("\n"));
  }
}
