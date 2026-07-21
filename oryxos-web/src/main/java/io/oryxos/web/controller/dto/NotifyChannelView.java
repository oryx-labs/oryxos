package io.oryxos.web.controller.dto;

import io.oryxos.core.notify.NotifyChannelDef;

/** 通知渠道视图（列表/详情返回）。 */
public record NotifyChannelView(String name, String type, String url, String description) {

  public static NotifyChannelView from(NotifyChannelDef d) {
    return new NotifyChannelView(d.name(), d.type(), d.url(), d.description());
  }
}
