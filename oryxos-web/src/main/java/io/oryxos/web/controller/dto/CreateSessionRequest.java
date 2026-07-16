package io.oryxos.web.controller.dto;

/** POST /sessions 请求体：绑定哪个 Profile、可选用户标识（缺省 "default"）。 */
public record CreateSessionRequest(String profile, String userId) {}
