package io.oryxos.provider;

import io.oryxos.core.OryxTool;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 把 OryxTool 的三要素（name/description/inputSchema）翻译成 Spring AI 的工具描述。
 *
 * <p>只翻译、不执行（宪法 II）：产物的 call() 永远抛异常——关闭框架内部工具执行后不会调用它， 抛出是"绝不执行"的第二道保险。
 */
public class ToolSchemaAdapter {

  public List<ToolCallback> toSpringAiTools(List<OryxTool> tools) {
    if (tools == null || tools.isEmpty()) {
      return List.of();
    }
    return tools.stream().map(SchemaOnlyCallback::new).map(ToolCallback.class::cast).toList();
  }

  /** 纯 schema 描述载体：不携带任何可执行逻辑。 */
  static final class SchemaOnlyCallback implements ToolCallback {

    private final ToolDefinition toolDefinition;

    SchemaOnlyCallback(OryxTool tool) {
      this.toolDefinition =
          ToolDefinition.builder()
              .name(tool.getName())
              .description(tool.getDescription())
              .inputSchema(tool.getInputSchema())
              .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
      return toolDefinition;
    }

    @Override
    public String call(String functionInput) {
      throw new IllegalStateException("Provider 只翻译工具、不执行工具: " + toolDefinition.name());
    }
  }
}
