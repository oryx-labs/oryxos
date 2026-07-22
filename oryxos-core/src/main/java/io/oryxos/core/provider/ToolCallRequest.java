package io.oryxos.core.provider;

/**
 * 模型提出的一次工具调用请求——只透传给上层，Provider 绝不执行。
 *
 * <p>{@code id} 是模型分配的调用标识：工具结果回填时按它与 assistant 的 tool_call 配对（OpenAI 协议），多步 ReAct
 * 才能让模型"看见"某个工具已调过、 继续下一步而不是反复重调（31 节修复）。老式两参构造（无 id）保留给单步 / 测试场景。
 */
public record ToolCallRequest(String id, String name, String argumentsJson) {

  public ToolCallRequest(String name, String argumentsJson) {
    this(null, name, argumentsJson);
  }
}
