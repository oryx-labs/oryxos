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

  /** 企微：无权限调用会话状态相关接口。 */
  private static final String ERRCODE_NO_PRIVILEGE = "errcode=48002";

  private static final String CONTENT_TYPE_JSON = "json";

  /** sync_msg：0=AMR（本机 ffmpeg 可解）。 */
  private static final int VOICE_FORMAT_AMR = 0;

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
  public WeixinKfSyncResult syncMsg(String openKfid, String callbackToken, String cursor) {
    ObjectNode body = MAPPER.createObjectNode();
    body.put("open_kfid", openKfid);
    if (callbackToken != null && !callbackToken.isBlank()) {
      body.put("token", callbackToken);
    }
    if (cursor != null && !cursor.isBlank()) {
      body.put("cursor", cursor);
    }
    body.put("limit", 1000);
    // AMR：本机 ffmpeg（Gyan full 等）可解；Silk 时官方 ffmpeg 无解码器，ASR 必失败。
    body.put("voice_format", VOICE_FORMAT_AMR);
    JsonNode root = postJson("/cgi-bin/kf/sync_msg", body);
    String nextCursor = root.path("next_cursor").asText("");
    int hasMore = root.path("has_more").asInt(0);
    List<JsonNode> messages = new ArrayList<>();
    JsonNode list = root.path("msg_list");
    if (list.isArray()) {
      list.forEach(messages::add);
    }
    return new WeixinKfSyncResult(messages, nextCursor, hasMore == 1);
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
    final int state;
    try {
      state = getServiceState(openKfid, externalUserId);
    } catch (IllegalStateException e) {
      // 48002：企业未开通/无权限调用会话状态 API；仍尝试 send_msg（入站已能 sync）。
      if (e.getMessage() != null && e.getMessage().contains(ERRCODE_NO_PRIVILEGE)) {
        return;
      }
      throw e;
    }
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

  @Override
  public WeixinKfMediaBlob downloadMedia(String mediaId) {
    if (mediaId == null || mediaId.isBlank()) {
      throw new IllegalArgumentException("media_id 为空");
    }
    String token = accessToken.get();
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("微信客服 access_token 为空");
    }
    String url =
        API_BASE
            + "/cgi-bin/media/get?access_token="
            + URLEncoder.encode(token, StandardCharsets.UTF_8)
            + "&media_id="
            + URLEncoder.encode(mediaId.strip(), StandardCharsets.UTF_8);
    guard.check(url);
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build();
      HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX) {
        throw new IllegalStateException(
            "微信客服 /cgi-bin/media/get HTTP "
                + response.statusCode()
                + ": "
                + sanitize(bodyAsText(response.body())));
      }
      byte[] body = response.body() == null ? new byte[0] : response.body();
      String contentType =
          response.headers().firstValue("Content-Type").orElse("application/octet-stream");
      if (looksLikeJsonError(contentType, body)) {
        JsonNode root = MAPPER.readTree(body);
        throw new IllegalStateException(
            "微信客服 /cgi-bin/media/get errcode="
                + root.path("errcode").asInt()
                + " errmsg="
                + root.path("errmsg").asText());
      }
      String fileName =
          fileNameFromDisposition(
              response
                  .headers()
                  .firstValue("Content-Disposition")
                  .or(() -> response.headers().firstValue("Content-disposition")));
      return new WeixinKfMediaBlob(body, contentType, fileName);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("微信客服 /cgi-bin/media/get 失败: " + e.getMessage(), e);
    }
  }

  private static boolean looksLikeJsonError(String contentType, byte[] body) {
    if (body == null || body.length == 0) {
      return false;
    }
    if (contentType != null
        && contentType.toLowerCase(java.util.Locale.ROOT).contains(CONTENT_TYPE_JSON)) {
      return true;
    }
    // 部分错误响应仍是 application/octet-stream，以 JSON 开头
    int i = 0;
    while (i < body.length && Character.isWhitespace(body[i])) {
      i++;
    }
    return i < body.length && body[i] == '{';
  }

  private static String bodyAsText(byte[] body) {
    if (body == null || body.length == 0) {
      return "";
    }
    return new String(body, StandardCharsets.UTF_8);
  }

  private static String fileNameFromDisposition(java.util.Optional<String> header) {
    if (header == null || header.isEmpty()) {
      return null;
    }
    String raw = header.get();
    if (raw == null || raw.isBlank()) {
      return null;
    }
    // filename="a.pdf" 或 filename*=UTF-8''a.pdf
    int star = raw.toLowerCase(java.util.Locale.ROOT).indexOf("filename*=");
    if (star >= 0) {
      String part = raw.substring(star + "filename*=".length()).trim();
      int q = part.indexOf("''");
      if (q >= 0) {
        part = part.substring(q + 2);
      }
      part = part.replace("\"", "").trim();
      return part.isEmpty() ? null : part;
    }
    int idx = raw.toLowerCase(java.util.Locale.ROOT).indexOf("filename=");
    if (idx < 0) {
      return null;
    }
    String part = raw.substring(idx + "filename=".length()).trim().replace("\"", "");
    int semi = part.indexOf(';');
    if (semi >= 0) {
      part = part.substring(0, semi).trim();
    }
    return part.isEmpty() ? null : part;
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
