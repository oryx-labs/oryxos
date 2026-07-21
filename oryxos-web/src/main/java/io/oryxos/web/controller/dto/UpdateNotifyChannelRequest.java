package io.oryxos.web.controller.dto;

/** 更新通知渠道请求体：name 在路径上，这里改 type / url / 描述。 */
public record UpdateNotifyChannelRequest(String type, String url, String description) {}
