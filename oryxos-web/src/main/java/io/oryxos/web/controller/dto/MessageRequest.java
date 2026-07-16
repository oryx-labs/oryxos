package io.oryxos.web.controller.dto;

/** 发消息 / 无状态调用请求体。content 必填、≤32KB（校验在 Controller）。 */
public record MessageRequest(String content) {}
