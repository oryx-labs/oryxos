package io.oryxos.web.controller.dto;

/** 新建 provider 请求体：name 全局唯一；非 mock 需 base-url（OpenAI 兼容端点）+ api-key。 */
public record CreateProviderRequest(
    String name, String apiKey, String baseUrl, String description) {}
