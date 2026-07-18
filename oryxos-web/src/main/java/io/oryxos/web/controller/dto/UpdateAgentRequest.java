package io.oryxos.web.controller.dto;

/** PUT /agents/{name} 请求体：覆写整段 AGENT.md 文本。 */
public record UpdateAgentRequest(String agentMarkdown) {}
