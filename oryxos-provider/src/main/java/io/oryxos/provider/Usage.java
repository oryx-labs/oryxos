package io.oryxos.provider;

/** token 用量；失败调用可整体为空。 */
public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
