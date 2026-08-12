package io.oryxos.web.controller.dto;

/** PUT /agents/{name}/basic 请求体：结构化编辑 Agent 基本信息（只动 AGENT.md frontmatter 的几个 key）。 */
public record UpdateAgentBasicRequest(String description, String provider, String model) {}
