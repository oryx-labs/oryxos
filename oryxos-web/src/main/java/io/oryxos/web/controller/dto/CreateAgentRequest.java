package io.oryxos.web.controller.dto;

/** POST /agents 请求体：只需 Agent 名 + 描述；后台按模板脚手架出完整目录。 */
public record CreateAgentRequest(String name, String description) {}
