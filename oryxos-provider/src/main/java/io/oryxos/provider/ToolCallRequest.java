package io.oryxos.provider;

/** 模型提出的一次工具调用请求——只透传给上层，Provider 绝不执行。 */
public record ToolCallRequest(String name, String argumentsJson) {}
