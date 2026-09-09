package io.oryxos.tool.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 通知推送共用 HTTP 出口：禁自动重定向，手动逐跳跟随并对<strong>每一跳</strong>过 {@code HTTP_REQUEST} 域名白名单——杜绝「白名单内 webhook
 * 入口 302 到白名单外 / 内网」的绕过（与 {@code HttpTools} 写路径同策略）。
 *
 * <p>终跳在 HTTP 2xx 时仍解析飞书 / 企微 / 钉钉常见业务错误码（{@code code}/{@code errcode}/{@code StatusCode}）；非 0
 * 视为失败。空 body、非 JSON、或无上述字段的通用 webhook 保持兼容。
 */
public final class NotifyPoster {

  private static final int MAX_REDIRECTS = 5;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
  private static final int STATUS_MOVED_PERMANENTLY = 301;
  private static final int STATUS_FOUND = 302;
  private static final int STATUS_SEE_OTHER = 303;
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String[] BUSINESS_CODE_FIELDS = {"errcode", "StatusCode", "code"};
  private static final String[] BUSINESS_MESSAGE_FIELDS = {
    "errmsg", "msg", "message", "description", "error"
  };

  private final Sandbox sandbox;
  private final RestClient hopClient;

  public NotifyPoster(Sandbox sandbox) {
    this.sandbox = Objects.requireNonNull(sandbox, "sandbox 不能为空");
    JdkClientHttpRequestFactory hopFactory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    hopFactory.setReadTimeout(READ_TIMEOUT);
    this.hopClient = RestClient.builder().requestFactory(hopFactory).build();
  }

  /** POST JSON body 到 url；3xx 时跟随 Location，每跳先过沙箱。301/302/303 下一跳改为 GET 且不再带 body。 */
  public void postJson(String url, Object body) {
    postJson(url, body, Map.of());
  }

  /** 同 {@link #postJson(String, Object)}，可带额外请求头（Bot Token 等）。 */
  public void postJson(String url, Object body, Map<String, String> headers) {
    sendJson(HttpMethod.POST, url, body, headers);
  }

  /**
   * PUT JSON。Matrix Client-Server {@code /send/m.room.message/{txn}} 必须用 PUT，POST 会被 homeserver 拒绝。
   */
  public void putJson(String url, Object body, Map<String, String> headers) {
    sendJson(HttpMethod.PUT, url, body, headers);
  }

  private void sendJson(HttpMethod method, String url, Object body, Map<String, String> headers) {
    String current = url;
    HttpMethod hopMethod = method;
    Object hopBody = body;
    Map<String, String> hopHeaders = headers == null ? Map.of() : headers;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, current));
      RestClient.RequestBodySpec spec = hopClient.method(hopMethod).uri(URI.create(current));
      hopHeaders.forEach(spec::header);
      if (hopBody != null) {
        spec.contentType(MediaType.APPLICATION_JSON).body(hopBody);
      }
      ResponseEntity<String> resp = spec.retrieve().toEntity(String.class);
      if (resp.getStatusCode().is3xxRedirection()) {
        String location = resp.getHeaders().getFirst("Location");
        if (location == null || location.isBlank()) {
          throw new IllegalStateException("通知推送收到重定向但缺少 Location: " + current);
        }
        if (switchesToGet(resp.getStatusCode().value())) {
          hopMethod = HttpMethod.GET;
          hopBody = null;
        }
        current = URI.create(current).resolve(location).toString();
        continue;
      }
      rejectVendorBusinessError(resp.getBody(), current);
      return;
    }
    throw new IllegalStateException("通知推送重定向次数过多，拒绝: " + url);
  }

  /** 飞书 {@code code}、企微/钉钉 {@code errcode}（及部分钉钉 {@code StatusCode}）非 0 时 fail-loud。缺字段或非数字不判失败。 */
  static void rejectVendorBusinessError(String responseBody, String url) {
    if (responseBody == null || responseBody.isBlank()) {
      return;
    }
    final JsonNode root;
    try {
      root = MAPPER.readTree(responseBody);
    } catch (JsonProcessingException ignored) {
      // 通用 webhook 可能返回非 JSON；不因此失败
      return;
    }
    if (root == null || !root.isObject()) {
      return;
    }
    JsonNode okNode = root.get("ok");
    if (okNode != null && okNode.isBoolean() && !okNode.asBoolean()) {
      throw new IllegalStateException(
          "通知推送业务失败 ok=false" + messageSuffix(root) + ": " + sanitizeUrl(url));
    }
    for (String field : BUSINESS_CODE_FIELDS) {
      JsonNode codeNode = root.get(field);
      if (codeNode == null || codeNode.isNull() || !codeNode.isValueNode()) {
        continue;
      }
      Long code = asBusinessCode(codeNode);
      if (code == null) {
        continue;
      }
      if (code != 0L) {
        throw new IllegalStateException(
            "通知推送业务失败 " + field + "=" + code + messageSuffix(root) + ": " + sanitizeUrl(url));
      }
      // 命中约定字段且为 0：已确认成功口径，不再用其它同义字段覆盖
      return;
    }
  }

  private static Long asBusinessCode(JsonNode node) {
    if (node.isNumber()) {
      return node.longValue();
    }
    if (node.isTextual()) {
      String text = node.asText().strip();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return Long.parseLong(text);
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String messageSuffix(JsonNode root) {
    for (String field : BUSINESS_MESSAGE_FIELDS) {
      JsonNode msg = root.get(field);
      if (msg != null && msg.isTextual() && !msg.asText().isBlank()) {
        return " (" + field + "=" + sanitizeMessage(msg.asText()) + ")";
      }
    }
    return "";
  }

  private static String sanitizeMessage(String value) {
    String flat = value.replace('\r', ' ').replace('\n', ' ').strip();
    return flat.length() <= 200 ? flat : flat.substring(0, 200);
  }

  private static String sanitizeUrl(String url) {
    return url == null ? "" : url.replace('\r', '_').replace('\n', '_');
  }

  private static boolean switchesToGet(int status) {
    return status == STATUS_MOVED_PERMANENTLY
        || status == STATUS_FOUND
        || status == STATUS_SEE_OTHER;
  }
}
