package io.oryxos.channel.matrix;

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
import java.util.UUID;

/** Matrix {@code PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}}。 */
public class MatrixMessageSender {

  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration TIMEOUT = Duration.ofSeconds(20);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String MSGTYPE_TEXT = "m.text";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String homeserver;
  private final String token;

  public MatrixMessageSender(OutboundGuard guard, String homeserver, String token) {
    this(HttpClient.newBuilder().connectTimeout(TIMEOUT).build(), guard, homeserver, token);
  }

  MatrixMessageSender(HttpClient http, OutboundGuard guard, String homeserver, String token) {
    this.http = http;
    this.guard = guard;
    this.homeserver = trimSlash(homeserver);
    this.token = token;
  }

  public void send(String roomId, String text) {
    String txn = UUID.randomUUID().toString();
    String url =
        homeserver
            + "/_matrix/client/v3/rooms/"
            + URLEncoder.encode(roomId, StandardCharsets.UTF_8)
            + "/send/m.room.message/"
            + txn;
    guard.check(url);
    try {
      ObjectNode body = MAPPER.createObjectNode();
      body.put("msgtype", MSGTYPE_TEXT);
      body.put("body", text == null ? "" : text);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json; charset=utf-8")
              .PUT(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        throw new IllegalStateException("Matrix 发消息失败 HTTP " + response.statusCode());
      }
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Matrix 发消息失败: " + e.getMessage(), e);
    }
  }

  static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
