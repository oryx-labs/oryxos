package io.oryxos.tool.notify;

import java.util.Map;

/**
 * 核心阶段唯一实现：通用 webhook——企业微信/飞书/钉钉的群机器人都收 webhook， 一档覆盖大部分场景，不逐家接签名算法与 AccessToken 刷新（留扩展阶段）。
 *
 * <p>失败口径（research D2）：对端非 2xx 走 RestClient 默认异常上抛、连接失败同样上抛—— "发出去没送到"与"没发出去"对 Agent 是同一件事，绝不静默吞掉。
 *
 * <p>出网经 {@link NotifyPoster}：禁自动重定向并每跳复检域名白名单。
 */
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public WebhookNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("webhook 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    poster.postJson(url, Map.of("content", content));
  }
}
