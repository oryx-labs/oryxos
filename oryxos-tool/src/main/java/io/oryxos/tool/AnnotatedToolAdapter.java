package io.oryxos.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.OryxTool;
import io.oryxos.core.ToolResult;
import org.springframework.ai.tool.ToolCallback;

/**
 * `@Tool` 注解管道的机制本体：把 Spring AI 扫描出的 {@link ToolCallback}（schema 已自动生成） 包装成 {@link
 * OryxTool}——内置工具与业务方方式三共用这条管道（TechSol §6.5/§6.6）。
 *
 * <p>宪法 II：这里只借 Spring AI 的 schema 生成与方法反射调用；执行的发起方是 17 节 ToolExecutor，
 * 不存在框架自动执行路径。异常不捕获——ToolExecutor 统一转失败结果并落审计。
 */
public class AnnotatedToolAdapter implements OryxTool {

  private final ToolCallback callback;

  public AnnotatedToolAdapter(ToolCallback callback) {
    this.callback = callback;
  }

  @Override
  public String getName() {
    return callback.getToolDefinition().name();
  }

  @Override
  public String getDescription() {
    return callback.getToolDefinition().description();
  }

  @Override
  public String getInputSchema() {
    return callback.getToolDefinition().inputSchema();
  }

  @Override
  public ToolResult execute(JsonNode input) {
    String output = callback.call(input == null ? "{}" : input.toString());
    return ToolResult.ok(output);
  }
}
