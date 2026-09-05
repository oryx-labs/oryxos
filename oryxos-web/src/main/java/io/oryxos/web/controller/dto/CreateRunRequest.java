package io.oryxos.web.controller.dto;

/** POST /api/v1/runs 请求体。 */
public record CreateRunRequest(String agentName, String content) {}
