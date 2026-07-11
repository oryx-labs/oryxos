package io.oryxos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.mcp.McpToolAdapter;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/** 课件《第20节》验收 harness：ToolRegistryTest——三种来源统一注册、重名拒绝、过滤不多不少。 */
class ToolRegistryTest {

  /** 直接实现来源的替身。 */
  private static OryxTool directTool(String name) {
    return new OryxTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name + " 描述";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return ToolResult.ok("ok");
      }
    };
  }

  /**
   * @Tool 注解来源的替身 bean。
   */
  static class AnnotatedBean {
    @Tool(name = "annotated_tool", description = "注解来源工具")
    public String annotatedTool(String input) {
      return input;
    }
  }

  private static McpToolAdapter mcpTool(String name) {
    McpSyncClient client = mock(McpSyncClient.class);
    return new McpToolAdapter(client, new McpSchema.Tool(name, name + " mcp", "{}"));
  }

  @Test
  @DisplayName("三种来源的工具都以 OryxTool 身份注册进来")
  void allThreeSourcesRegisterAsOryxTool() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("notify"));
    registry.registerAnnotated(new AnnotatedBean());
    registry.register(mcpTool("github_search"));

    assertTrue(registry.contains("notify"));
    assertTrue(registry.contains("annotated_tool"));
    assertTrue(registry.contains("github_search"));
    assertEquals(3, registry.all().size());
  }

  @Test
  @DisplayName("重名注册被拒绝并点名")
  void duplicateNameIsRejected() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("shell"));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> registry.register(directTool("shell")));

    assertTrue(ex.getMessage().contains("shell"));
  }

  @Test
  @DisplayName("按Profile的tools字段过滤_子集恰好等于声明列表")
  void filterByProfileToolsIsExact() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("read_file"));
    registry.register(directTool("http_get"));
    registry.register(directTool("shell"));

    List<OryxTool> filtered =
        registry.filterByNames(List.of("read_file", "http_get", "not_registered"));

    // 不多（shell 没混进来）不少（两个声明的都在）；未知名跳过不报错
    assertEquals(
        List.of("read_file", "http_get"), filtered.stream().map(OryxTool::getName).toList());
  }
}
