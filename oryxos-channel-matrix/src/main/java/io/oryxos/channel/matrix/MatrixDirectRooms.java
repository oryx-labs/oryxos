package io.oryxos.channel.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析并记住 {@code m.direct}。该事件在 {@code /sync} <strong>顶层</strong> {@code account_data}，不是房间
 * account_data；内容是整份覆盖，须跨增量 sync 保留。
 */
final class MatrixDirectRooms {

  private static final String FIELD_ACCOUNT_DATA = "account_data";
  private static final String FIELD_EVENTS = "events";
  private static final String FIELD_TYPE = "type";
  private static final String FIELD_CONTENT = "content";
  private static final String TYPE_DIRECT = "m.direct";

  private final Set<String> roomIds = ConcurrentHashMap.newKeySet();

  void mergeFromSync(JsonNode root) {
    if (root == null || !root.isObject()) {
      return;
    }
    JsonNode events = root.path(FIELD_ACCOUNT_DATA).path(FIELD_EVENTS);
    if (!events.isArray()) {
      return;
    }
    for (JsonNode event : events) {
      if (TYPE_DIRECT.equals(event.path(FIELD_TYPE).asText(""))) {
        replaceFromContent(event.path(FIELD_CONTENT));
      }
    }
  }

  void replaceFromContent(JsonNode content) {
    roomIds.clear();
    if (content == null || !content.isObject()) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> users = content.fields();
    while (users.hasNext()) {
      JsonNode rooms = users.next().getValue();
      if (!rooms.isArray()) {
        continue;
      }
      for (JsonNode room : rooms) {
        String id = room.asText("");
        if (!id.isBlank()) {
          roomIds.add(id);
        }
      }
    }
  }

  void remember(String roomId) {
    if (roomId != null && !roomId.isBlank()) {
      roomIds.add(roomId);
    }
  }

  boolean isDirect(String roomId) {
    return roomId != null && roomIds.contains(roomId);
  }

  int size() {
    return roomIds.size();
  }
}
