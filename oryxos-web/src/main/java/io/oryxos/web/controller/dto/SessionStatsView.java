package io.oryxos.web.controller.dto;

/** GET /api/v1/sessions/stats 视图：活跃、归档、总会话计数。 */
public record SessionStatsView(int active, int archived, int total) {}
