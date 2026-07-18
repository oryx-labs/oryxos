package io.oryxos.web.controller.dto;

/** PUT /schedules/{id} 请求体：启用/停用一条定时任务。 */
public record SetEnabledRequest(Boolean enabled) {}
