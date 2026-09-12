package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundChannelRegistry;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.web.error.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 入站 IM 共享 Webhook 面（026 P0）：按渠道名查找运行中适配器；仅 {@link InboundWebhookHandler} 受理，否则 404。
 *
 * <p>响应体按适配器原样回写（挑战握手 / 验签回执），不套 {@code ApiResponse} 信封。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "core-stage web API is unauthenticated by design; registry 是 Runtime 装配的共享单例。")
@RestController
@RequestMapping("/api/v1/channels/inbound")
public class ChannelInboundWebhookController {

  private static final String CHARSET_ATTR = "charset=";
  private static final String CHARSET_GBK_HINT = "charset=gbk";
  private static final String BIZ_CONTENT_HINT = "biz_content=";
  private static final String ALIPAY_SERVICE_HINT = "alipay.service";
  private static final String GBK_NAME = "GBK";
  private static final String FORM_PAIR_SEP = "&";
  private static final char QUOTE = '"';
  private static final int FORM_CHARSET_PROBE_BYTES = 512;
  private static final int QUOTED_CHARSET_MIN_LEN = 2;

  private final InboundChannelRegistry registry;

  public ChannelInboundWebhookController(InboundChannelRegistry registry) {
    this.registry = registry;
  }

  @GetMapping("/{name}")
  public ResponseEntity<String> inboundGet(
      @PathVariable String name,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] rawBody) {
    return inbound(name, request, rawBody);
  }

  @PostMapping("/{name}")
  public ResponseEntity<String> inboundPost(
      @PathVariable String name,
      HttpServletRequest request,
      @RequestBody(required = false) byte[] rawBody) {
    return inbound(name, request, rawBody);
  }

  private ResponseEntity<String> inbound(String name, HttpServletRequest request, byte[] rawBody) {
    InboundChannelAdapter adapter =
        registry
            .get(name)
            .orElseThrow(() -> new ResourceNotFoundException("入站 webhook 渠道不存在或未上线: " + name));
    if (!(adapter instanceof InboundWebhookHandler handler)) {
      throw new ResourceNotFoundException("渠道 " + name + " 不支持入站 webhook");
    }
    byte[] raw = rawBody == null ? new byte[0] : rawBody;
    Map<String, String> params = queryMap(request);
    // @RequestBody 会吃掉表单流，servlet ParameterMap 常为空；支付宝生活号网关为 GBK form。
    if (raw.length > 0 && isFormUrlEncoded(request.getContentType())) {
      Map<String, String> form =
          parseFormUrlEncoded(raw, resolveFormCharset(request.getContentType(), raw));
      if (params.isEmpty()) {
        params = form;
      } else {
        Map<String, String> merged = new LinkedHashMap<>(params);
        form.forEach(merged::putIfAbsent);
        params = merged;
      }
    }
    WebhookRequest webhookRequest =
        new WebhookRequest(request.getMethod(), params, headerMap(request), bodyText(raw));
    WebhookResponse response = handler.onWebhook(webhookRequest);
    MediaType mediaType = MediaType.parseMediaType(response.contentType());
    return ResponseEntity.status(response.status()).contentType(mediaType).body(response.body());
  }

  private static String bodyText(byte[] rawBody) {
    if (rawBody == null || rawBody.length == 0) {
      return "";
    }
    return new String(rawBody, StandardCharsets.UTF_8);
  }

  private static boolean isFormUrlEncoded(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return false;
    }
    String lower = asciiLower(contentType);
    return lower.startsWith(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
  }

  /** 表单编码：Content-Type charset 优先；支付宝生活号通知默认 GBK（常写在 form 字段而非 header）。 */
  private static Charset resolveFormCharset(String contentType, byte[] raw) {
    Charset fromHeader = charsetFromContentType(contentType);
    if (fromHeader != null) {
      return fromHeader;
    }
    String probe =
        new String(
            raw, 0, Math.min(raw.length, FORM_CHARSET_PROBE_BYTES), StandardCharsets.ISO_8859_1);
    String lower = asciiLower(probe);
    if (lower.contains(CHARSET_GBK_HINT)
        || lower.contains(BIZ_CONTENT_HINT)
        || lower.contains(ALIPAY_SERVICE_HINT)) {
      return Charset.forName(GBK_NAME);
    }
    return StandardCharsets.UTF_8;
  }

  private static Charset charsetFromContentType(String contentType) {
    if (contentType == null) {
      return null;
    }
    String lower = asciiLower(contentType);
    int idx = lower.indexOf(CHARSET_ATTR);
    if (idx < 0) {
      return null;
    }
    String value = contentType.substring(idx + CHARSET_ATTR.length()).trim();
    int semi = value.indexOf(';');
    if (semi >= 0) {
      value = value.substring(0, semi).trim();
    }
    if (value.length() >= QUOTED_CHARSET_MIN_LEN
        && value.charAt(0) == QUOTE
        && value.charAt(value.length() - 1) == QUOTE) {
      value = value.substring(1, value.length() - 1);
    }
    if (value.isEmpty()) {
      return null;
    }
    try {
      return Charset.forName(value);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 支付宝兼容入口复用同一套 form 解码（保留 {@code +}）。 */
  static Map<String, String> parseFormUrlEncodedForCompat(byte[] raw, Charset charset) {
    return parseFormUrlEncoded(raw, charset);
  }

  private static Map<String, String> parseFormUrlEncoded(byte[] raw, Charset charset) {
    Map<String, String> out = new LinkedHashMap<>();
    String text = new String(raw, charset);
    if (text.isEmpty()) {
      return out;
    }
    for (String pair : text.split(FORM_PAIR_SEP)) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
      String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";
      String key = formDecode(rawKey, charset);
      String val = formDecode(rawVal, charset);
      if (!key.isEmpty()) {
        out.putIfAbsent(key, val);
      }
    }
    return out;
  }

  /**
   * 仅解码 {@code %XX}；保留 {@code +}。标准 {@link URLDecoder} 会把 {@code +} 变空格，打坏支付宝 Base64 {@code sign}。
   */
  private static String formDecode(String raw, Charset charset) {
    if (raw == null || raw.isEmpty()) {
      return "";
    }
    return URLDecoder.decode(raw.replace("+", "%2B"), charset);
  }

  private static Map<String, String> queryMap(HttpServletRequest request) {
    Map<String, String> query = new LinkedHashMap<>();
    request
        .getParameterMap()
        .forEach(
            (key, values) -> {
              if (values != null && values.length > 0 && values[0] != null) {
                query.put(key, values[0]);
              }
            });
    return query;
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
        headers.put(asciiLower(name), request.getHeader(name));
      }
    }
    return headers;
  }

  /** 只折 A–Z，避免 Locale 大小写映射触发 SpotBugs IMPROPER_UNICODE。 */
  static String asciiLower(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    char[] chars = value.toCharArray();
    boolean changed = false;
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
        changed = true;
      }
    }
    return changed ? new String(chars) : value;
  }
}
