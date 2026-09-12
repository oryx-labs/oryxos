package io.oryxos.channel.alipay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 生活号 {@code alipay.open.public.message.custom.send}。 */
final class AlipayApiClient implements AlipayClient {

  static final String API_BASE = "https://openapi.alipay.com/gateway.do";
  static final String METHOD_CUSTOM_SEND = "alipay.open.public.message.custom.send";

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 300;

  /** 支付宝 OpenAPI 业务成功码。 */
  private static final String SUCCESS_CODE = "10000";

  private static final Charset CHARSET = StandardCharsets.UTF_8;
  private static final DateTimeFormatter TS =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.ofHours(8));

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String appId;
  private final AlipayRsa2 rsa;

  AlipayApiClient(OutboundGuard guard, String appId, AlipayRsa2 rsa) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, appId, rsa);
  }

  AlipayApiClient(HttpClient http, OutboundGuard guard, String appId, AlipayRsa2 rsa) {
    this.http = http;
    this.guard = guard;
    this.appId = appId;
    this.rsa = rsa;
  }

  @Override
  public void sendText(String toUserId, String text) {
    if (toUserId == null || toUserId.isBlank()) {
      throw new IllegalArgumentException("to_user_id 为空");
    }
    ObjectNode biz = MAPPER.createObjectNode();
    biz.put("to_user_id", toUserId);
    biz.put("msg_type", "text");
    biz.put("chat", "1");
    biz.putObject("text").put("content", text == null ? "" : text);
    Map<String, String> params = new LinkedHashMap<>();
    params.put("app_id", appId);
    params.put("method", METHOD_CUSTOM_SEND);
    params.put("format", "JSON");
    params.put("charset", "UTF-8");
    params.put("sign_type", AlipayRsa2.SIGN_TYPE);
    params.put("timestamp", TS.format(ZonedDateTime.now(ZoneOffset.ofHours(8))));
    params.put("version", "1.0");
    try {
      params.put("biz_content", MAPPER.writeValueAsString(biz));
    } catch (Exception e) {
      throw new IllegalStateException("组装支付宝 biz_content 失败: " + e.getMessage(), e);
    }
    params.put("sign", rsa.sign(params, CHARSET));
    postForm(params);
  }

  private void postForm(Map<String, String> params) {
    guard.check(API_BASE);
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> e : params.entrySet()) {
      if (body.length() > 0) {
        body.append('&');
      }
      body.append(URLEncoder.encode(e.getKey(), CHARSET))
          .append('=')
          .append(URLEncoder.encode(e.getValue(), CHARSET));
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(API_BASE))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
              .POST(HttpRequest.BodyPublishers.ofString(body.toString(), CHARSET))
              .build();
      HttpResponse<String> response =
          http.send(request, HttpResponse.BodyHandlers.ofString(CHARSET));
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX) {
        throw new IllegalStateException(
            "支付宝 custom.send HTTP " + response.statusCode() + ": " + sanitize(response.body()));
      }
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      JsonNode resp =
          root.has("alipay_open_public_message_custom_send_response")
              ? root.get("alipay_open_public_message_custom_send_response")
              : root;
      String code = resp.path("code").asText("");
      if (!code.isEmpty() && !SUCCESS_CODE.equals(code)) {
        throw new IllegalStateException(
            "支付宝 custom.send code="
                + code
                + " sub_code="
                + resp.path("sub_code").asText()
                + " msg="
                + resp.path("msg").asText()
                + " sub_msg="
                + resp.path("sub_msg").asText());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("支付宝 custom.send 失败: " + e.getMessage(), e);
    }
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.length() > 200 ? value.substring(0, 200) : value;
    return trimmed.replace('\r', '_').replace('\n', '_');
  }
}
