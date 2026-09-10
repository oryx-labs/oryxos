package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 微信客服 OpenAPI 面（便于单测注入）。 */
interface WeixinKfClient {

  SyncResult syncMsg(String openKfid, String callbackToken, String cursor);

  void sendText(String openKfid, String externalUserId, String text);

  void ensureAiReception(String openKfid, String externalUserId);

  record SyncResult(List<JsonNode> messages, String nextCursor, boolean hasMore) {}
}
