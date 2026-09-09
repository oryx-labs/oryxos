package io.oryxos.tool.notify;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Matrix Client-Server 出站（type: matrix）。
 *
 * <p>{@code config.homeserver} + {@code config.token} + {@code config.room_id}；或直接 {@code url}。
 */
public class MatrixNotifyAdapter implements NotifyChannelAdapter {

  private final NotifyPoster poster;

  public MatrixNotifyAdapter(NotifyPoster poster) {
    this.poster = poster;
  }

  @Override
  public void send(NotifyTarget target, String content) {
    String url = target.config().get("url");
    String token = target.config().get("token");
    if (url == null || url.isBlank()) {
      String homeserver = target.config().get("homeserver");
      String roomId = target.config().get("room_id");
      if (homeserver == null
          || homeserver.isBlank()
          || token == null
          || token.isBlank()
          || roomId == null
          || roomId.isBlank()) {
        throw new IllegalArgumentException("matrix 渠道缺少 url，或缺少 homeserver + token + room_id");
      }
      String txn = UUID.randomUUID().toString();
      url =
          trimSlash(homeserver)
              + "/_matrix/client/v3/rooms/"
              + encodeRoom(roomId)
              + "/send/m.room.message/"
              + txn;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("msgtype", "m.text");
    body.put("body", content);
    Map<String, String> headers = new LinkedHashMap<>();
    if (token != null && !token.isBlank()) {
      headers.put("Authorization", "Bearer " + token);
    }
    poster.putJson(url, body, headers);
  }

  private static String trimSlash(String homeserver) {
    String s = homeserver.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String encodeRoom(String roomId) {
    return roomId.replace("!", "%21").replace(":", "%3A");
  }
}
