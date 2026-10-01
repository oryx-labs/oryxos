package io.oryxos.channel.wecom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.OutboundGuard;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 企微回复发送器：优先 {@code aibot_send_msg}（markdown）主动推送；出站前过 {@link OutboundGuard}。
 *
 * <p>智能机器人长连接要求会话内曾有用户消息后才能主动推送——入站编排路径天然满足。群聊 B4：{@code replyToMessageId} 非空时在 body 附带 {@code
 * quote.msgid}，使回复与用户提问可对应。
 */
public class WeComMessageSender {

  static final int DEFAULT_CHUNK_SIZE = 3500;
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Consumer<ObjectNode> transport;
  private final OutboundGuard guard;
  private final String outboundUrl;
  private final int chunkSize;
  private final Map<String, Integer> chatTypes = new ConcurrentHashMap<>();

  public WeComMessageSender(
      Consumer<ObjectNode> transport, OutboundGuard guard, String outboundUrl, int chunkSize) {
    this.transport = transport;
    this.guard = guard;
    this.outboundUrl = outboundUrl;
    this.chunkSize = chunkSize <= 0 ? DEFAULT_CHUNK_SIZE : chunkSize;
  }

  /** 记录会话类型（1 单聊 / 2 群聊），供后续主动推送。 */
  public void rememberChatType(String chatId, int chatType) {
    if (chatId != null && !chatId.isBlank()) {
      chatTypes.put(chatId, chatType);
    }
  }

  public void send(String chatId, String text) {
    send(chatId, text, null);
  }

  /** 发送 markdown 回复；群聊 {@code replyToMessageId} 非空时附带 {@code quote.msgid}（契约 B4）。 */
  public void send(String chatId, String text, String replyToMessageId) {
    guard.check(outboundUrl);
    int chatType = chatTypes.getOrDefault(chatId, 0);
    for (String chunk : segment(text == null ? "" : text, chunkSize)) {
      ObjectNode headers = MAPPER.createObjectNode();
      headers.put("req_id", UUID.randomUUID().toString());
      ObjectNode markdown = MAPPER.createObjectNode();
      markdown.put("content", chunk);
      ObjectNode body = MAPPER.createObjectNode();
      body.put("chatid", chatId);
      if (chatType > 0) {
        body.put("chat_type", chatType);
      }
      if (replyToMessageId != null && !replyToMessageId.isBlank()) {
        body.putObject("quote").put("msgid", replyToMessageId);
      }
      body.put("msgtype", "markdown");
      body.set("markdown", markdown);
      ObjectNode frame = MAPPER.createObjectNode();
      frame.put("cmd", "aibot_send_msg");
      frame.set("headers", headers);
      frame.set("body", body);
      transport.accept(frame);
    }
  }

  static List<String> segment(String text, int chunkSize) {
    if (text.isEmpty()) {
      return List.of("");
    }
    List<String> parts = new ArrayList<>();
    for (int i = 0; i < text.length(); i += chunkSize) {
      parts.add(text.substring(i, Math.min(text.length(), i + chunkSize)));
    }
    return parts;
  }
}
