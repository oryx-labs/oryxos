package io.oryxos.web.controller.dto;

/** POST /agents/{name}/trigger 响应：异步触发已受理，立即返回执行记录 id 与状态（第 32 节）。 */
public record TriggerResponse(long executionId, String status) {}
