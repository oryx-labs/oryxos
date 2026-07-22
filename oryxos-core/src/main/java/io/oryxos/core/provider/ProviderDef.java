package io.oryxos.core.provider;

/**
 * Provider 定义（跨模块值对象，31 节动态 provider）：一个可运行时增删改的 LLM 接入点。
 *
 * <p>{@code name} 全局唯一、Agent 的 AGENT.md 按它引用；{@code baseUrl} + {@code apiKey} 是 OpenAI
 * 兼容端点的接入参数（{@code name == "mock"} 时二者可空，走内置假模型）。放 core 是因为 web（CRUD）、oryxos-provider（按名动态建
 * ChatModel）、oryxos-storage（JPA 实现）都要认它，而三者都已依赖 core。
 */
public record ProviderDef(String name, String apiKey, String baseUrl, String description) {}
