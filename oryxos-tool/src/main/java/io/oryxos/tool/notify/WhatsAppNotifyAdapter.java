package io.oryxos.tool.notify;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WhatsApp Cloud API 出站（type: whatsapp）。
 *
 * <p>会话窗外的主动触达应走已审核模板（{@code config.template}）；未给模板时发会话内文本，由 Graph 或入站适配器拒绝窗外发送。
 */
public class WhatsAppNotifyAdapter implements NotifyChannelAdapter {

  static final String GRAPH_BASE = "https://graph.facebook.com/v21.0/";

  private final NotifyPoster poster;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification =
          "NotifyPoster is a Spring singleton shared by notify adapters; storing the reference is intentional.")
  public WhatsAppNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    String token = target.config().get("token");
    String phoneNumberId = target.config().get("phone_number_id");
    String to = target.config().get("to");
    if (url == null || url.isBlank()) {
      if (token == null
          || token.isBlank()
          || phoneNumberId == null
          || phoneNumberId.isBlank()
          || to == null
          || to.isBlank()) {
        throw new IllegalArgumentException("whatsapp 渠道缺少 url，或缺少 token + phone_number_id + to");
      }
      url = GRAPH_BASE + phoneNumberId + "/messages";
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messaging_product", "whatsapp");
    body.put("to", to == null ? "" : to);
    String template = target.config().get("template");
    if (template != null && !template.isBlank()) {
      body.put("type", "template");
      body.put("template", Map.of("name", template, "language", Map.of("code", "en")));
    } else {
      body.put("type", "text");
      body.put("text", Map.of("body", content));
    }
    Map<String, String> headers = new LinkedHashMap<>();
    if (token != null && !token.isBlank()) {
      headers.put("Authorization", "Bearer " + token);
    }
    poster.postJson(url, body, headers);
  }
}
