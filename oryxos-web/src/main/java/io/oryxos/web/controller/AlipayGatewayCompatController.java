package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundChannelRegistry;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 兼容支付宝「激活开发者模式」偶发把网关截成 {@code /api/v1/} 的情况：若 form 像生活号网关，则转给 {@code ops-alipay}。 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "compat shim for Alipay gateway truncation; registry is Runtime singleton.")
@RestController
public class AlipayGatewayCompatController {

  private static final String CHANNEL = "ops-alipay";

  private final InboundChannelRegistry registry;

  public AlipayGatewayCompatController(InboundChannelRegistry registry) {
    this.registry = registry;
  }

  @PostMapping(path = {"/api/v1", "/api/v1/"})
  public ResponseEntity<String> alipayTruncatedGateway(
      HttpServletRequest request, @RequestBody(required = false) byte[] rawBody) {
    byte[] raw = rawBody == null ? new byte[0] : rawBody;
    if (!looksLikeAlipayGateway(raw)) {
      return ResponseEntity.notFound().build();
    }
    Optional<InboundChannelAdapter> adapter = registry.get(CHANNEL);
    if (adapter.isEmpty() || !(adapter.get() instanceof InboundWebhookHandler handler)) {
      return ResponseEntity.status(503).body("ops-alipay offline");
    }
    Map<String, String> params =
        ChannelInboundWebhookController.parseFormUrlEncodedForCompat(raw, Charset.forName("GBK"));
    WebhookRequest webhookRequest =
        new WebhookRequest(request.getMethod(), params, headerMap(request), "");
    WebhookResponse response = handler.onWebhook(webhookRequest);
    MediaType mediaType = MediaType.parseMediaType(response.contentType());
    return ResponseEntity.status(response.status()).contentType(mediaType).body(response.body());
  }

  private static boolean looksLikeAlipayGateway(byte[] raw) {
    if (raw.length == 0) {
      return false;
    }
    String probe = new String(raw, 0, Math.min(raw.length, 800), StandardCharsets.ISO_8859_1);
    String lower = probe.toLowerCase(Locale.ROOT);
    return lower.contains("alipay.service") || lower.contains("biz_content=");
  }

  private static Map<String, String> headerMap(HttpServletRequest request) {
    Map<String, String> headers = new LinkedHashMap<>();
    Enumeration<String> names = request.getHeaderNames();
    if (names == null) {
      return headers;
    }
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      if (name != null) {
        headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
      }
    }
    return headers;
  }
}
