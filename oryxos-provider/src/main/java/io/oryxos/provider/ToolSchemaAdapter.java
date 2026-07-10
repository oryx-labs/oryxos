package io.oryxos.provider;

import io.oryxos.core.OryxTool;
import java.util.List;
import org.springframework.ai.model.function.FunctionCallback;

/**
 * 把 OryxTool 的三要素（name/description/inputSchema）翻译成 Spring AI 的工具描述。
 *
 * <p>只翻译、不执行（宪法 II）：产物的 call() 永远抛异常——proxyToolCalls 模式下框架不会调用它， 抛出是"绝不执行"的第二道保险。
 */
public class ToolSchemaAdapter {

  public List<FunctionCallback> toSpringAiTools(List<OryxTool> tools) {
    if (tools == null || tools.isEmpty()) {
      return List.of();
    }
    return tools.stream().map(SchemaOnlyCallback::new).map(FunctionCallback.class::cast).toList();
  }

  /** 纯 schema 描述载体：不携带任何可执行逻辑。 */
  static final class SchemaOnlyCallback implements FunctionCallback {

    private final String name;
    private final String description;
    private final String inputSchema;

    SchemaOnlyCallback(OryxTool tool) {
      this.name = tool.getName();
      this.description = tool.getDescription();
      this.inputSchema = tool.getInputSchema();
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public String getInputTypeSchema() {
      return inputSchema;
    }

    @Override
    public String call(String functionInput) {
      throw new IllegalStateException("Provider 只翻译工具、不执行工具: " + name);
    }
  }
}
