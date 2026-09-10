package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 微信客服 OpenAPI：sync_msg / send_msg / service_state。 */
final class WeixinKfApiClient implements WeixinKfClient {

  static final String API_BASE = WeixinKfAccessTokenClient.API_BASE;

  /** 由智能助手接待。 */
  static final int STATE_AI = 1;

  /** 未处理（新接入）。 */
  static final int STATE_UNTREATED = 0;

  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX = 300;

  private final HttpClient http;
  private final OutboundGuard guard;
  private final Supplier<String> accessToken;

  WeixinKfApiClient(OutboundGuard guard, Supplier<String> accessToken) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, accessToken);
  }

  WeixinKfApiClient(HttpClient http, OutboundGuard guard, Supplier<String> accessToken) {
    this.http = http;
    this.guard = guard;
    this.accessToken = accessToken;
  }

  @Override
  public SyncResult syncMsg(String openKfid, String callbackToken, String cursor) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("open_kfid", openKfid);
    if (callbackToken != null && !callbackToken.isBlank()) {
      body.put("token", callbackToken);
    }
    if (cursor != null && !cursor.isBlank()) {
      body.put("cursor", cursor);
    }
    body.put("limit", 1000);
    JsonNode root = postJson("/cgi-bin/kf/sync_msg", body);
    String nextCursor = root.path("next_cursor").asText("");
    int hasMore = root.path("has_more").asInt(0);
    List<JsonNode> messages = new ArrayList<>();
    JsonNode list = root.path("msg_list");
    if (list.isArray()) {
      list.forEach(messages::add);
    }
    return new SyncResult(List.copyOf(messages), nextCursor, hasMore == 1);
  }

  @Override
  public void sendText(String openKfid, String externalUserId, String text) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("touser", externalUserId);
    body.put("open_kfid", openKfid);
    body.put("msgtype", "text");
    body.putObject("text").put("content", text == null ? "" : text);
    postJson("/cgi-bin/kf/send_msg", body);
  }

  int getServiceState(String openKfid, String externalUserId) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("open_kfid", openKfid);
    body.put("external_userid", externalUserId);
    JsonNode root = postJson("/cgi-bin/kf/service_state/get", body);
    return root.path("service_state").asInt(-1);
  }

  @Override
  public void ensureAiReception(String openKfid, String externalUserId) {
    int state = getServiceState(openKfid, externalUserId);
    if (state == STATE_AI || state == STATE_UNTREATED) {
      if (state == STATE_UNTREATED) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("open_kfid", openKfid);
        body.put("external_userid", externalUserId);
        body.put("service_state", STATE_AI);
        postJson("/cgi-bin/kf/service_state/trans", body);
      }
      return;
    }
    if (state < 0) {
      throw new IllegalStateException("微信客服无法读取会话状态（user=" + externalUserId + "）");
    }
    throw new IllegalStateException(
        "微信客服会话非智能助手/未处理态（state=" + state + "，user=" + externalUserId + "），拒绝 API 发信");
  }

  private JsonNode postJson(String path, ObjectNode body) {
    String token = accessToken.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("微信客服 access_token 为空");
    }
    String url =
        API_BASE + path + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Content-Type", "application/json; charset=utf-8")
              .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX) {
        throw new IllegalStateException(
            "微信客服 " + path + " HTTP " + response.statusCode() + ": " + sanitize(response.body()));
      }
      JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
      int errcode = root.path("errcode").asInt(0);
      if (errcode != 0) {
        throw new IllegalStateException(
            "微信客服 " + path + " errcode=" + errcode + " errmsg=" + root.path("errmsg").asText());
      }
      return root;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("微信客服 " + path + " 失败: " + e.getMessage(), e);
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
