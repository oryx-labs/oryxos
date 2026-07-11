package io.oryxos.tool.notify;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 核心阶段唯一实现：通用 webhook——企业微信/飞书/钉钉的群机器人都收 webhook， 一档覆盖大部分场景，不逐家接签名算法与 AccessToken 刷新（留扩展阶段）。
 *
 * <p>失败口径（research D2）：对端非 2xx 走 RestClient 默认异常上抛、连接失败同样上抛—— "发出去没送到"与"没发出去"对 Agent 是同一件事，绝不静默吞掉。
 */
public class WebhookNotifyAdapter implements NotifyChannelAdapter {

  private final RestClient restClient;

  public WebhookNotifyAdapter(RestClient restClient) {
    // 防御性副本：不持有调用方可继续改动的实例（SpotBugs EI_EXPOSE_REP2）
    this.restClient = restClient.mutate().build();
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("webhook 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("content", content))
        .retrieve()
        .toBodilessEntity();
  }
}
