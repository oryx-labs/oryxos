package io.oryxos.web.controller.dto;

/** 更新 provider 请求体：name 在路径上，这里改 api-key / base-url / 描述。 */
public record UpdateProviderRequest(String apiKey, String baseUrl, String description) {}
