package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** {@code kf/sync_msg} 一页结果。 */
final class WeixinKfSyncResult {

  private final List<JsonNode> messages;
  private final String nextCursor;
  private final boolean hasMore;

  WeixinKfSyncResult(List<JsonNode> messages, String nextCursor, boolean hasMore) {
    this.messages = messages == null ? List.of() : List.copyOf(messages);
    this.nextCursor = nextCursor == null ? "" : nextCursor;
    this.hasMore = hasMore;
  }

  List<JsonNode> messages() {
    return messages;
  }

  String nextCursor() {
    return nextCursor;
  }

  boolean hasMore() {
    return hasMore;
  }
}
