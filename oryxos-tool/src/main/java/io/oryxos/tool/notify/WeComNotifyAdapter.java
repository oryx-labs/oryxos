package io.oryxos.tool.notify;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 企业微信群机器人（type: wecom）。
 *
 * <p>webhook 形态与通用档相同，仅 body 格式不同：{@code {"msgtype":"text","text":{"content":"..."}}}。
 * 注意这是"群机器人"档——"应用消息"（corpid/corpsecret 换 AccessToken）属扩展阶段，不在此。
 */
public class WeComNotifyAdapter implements NotifyChannelAdapter {

  private final RestClient restClient;

  public WeComNotifyAdapter(RestClient restClient) {
    this.restClient = restClient.mutate().build();
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("wecom 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("msgtype", "text", "text", Map.of("content", content)))
        .retrieve()
        .toBodilessEntity();
  }
}
