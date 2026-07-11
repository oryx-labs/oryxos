package io.oryxos.tool.notify;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 钉钉自定义机器人（type: dingtalk）。
 *
 * <p>body 格式：{@code {"msgtype":"text","text":{"content":"..."}}}。钉钉机器人必须启用一种安全设置：
 * "自定义关键词"最省事（把关键词写进 Skill 输出要求即可，本实现零处理）；若群里开的是"加签"， 在 config 里配 {@code secret}（走 ${ENV}
 * 占位），发送时按官方算法拼 timestamp+sign 到 URL。
 */
public class DingTalkNotifyAdapter implements NotifyChannelAdapter {

  private final RestClient restClient;

  public DingTalkNotifyAdapter(RestClient restClient) {
    this.restClient = restClient.mutate().build();
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("dingtalk 渠道缺少 url 配置（notify_channels 条目需要 url 键）");
    }
    String secret = target.config().get("secret");
    if (secret != null && !secret.isBlank()) {
      url = appendSignature(url, secret);
    }
    restClient
        .post()
        .uri(url)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("msgtype", "text", "text", Map.of("content", content)))
        .retrieve()
        .toBodilessEntity();
  }

  /** 钉钉"加签"安全设置：sign = urlEncode(base64(HmacSHA256(timestamp + "\n" + secret, secret)))。 */
  private static String appendSignature(String url, String secret) {
    long timestamp = System.currentTimeMillis();
    String stringToSign = timestamp + "\n" + secret;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String sign =
          URLEncoder.encode(
              Base64.getEncoder()
                  .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
              StandardCharsets.UTF_8);
      String separator = url.contains("?") ? "&" : "?";
      return url + separator + "timestamp=" + timestamp + "&sign=" + sign;
    } catch (GeneralSecurityException e) {
      // HmacSHA256 是 JDK 必备算法，走到这里说明运行环境异常——显式失败胜过发一条必被拒的请求
      throw new IllegalStateException("钉钉加签计算失败", e);
    }
  }
}
