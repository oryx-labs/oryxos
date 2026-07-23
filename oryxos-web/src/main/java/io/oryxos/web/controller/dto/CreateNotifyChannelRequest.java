package io.oryxos.web.controller.dto;

/** 新建通知渠道请求体：name 全局唯一，type∈{webhook,feishu,wecom,dingtalk}，url 为 webhook 地址。 */
public record CreateNotifyChannelRequest(
    String name, String type, String url, String description) {}
