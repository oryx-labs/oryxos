package io.oryxos.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企微智能机器人 {@code aibot_msg_callback} → 归一化 {@link InboundMessage}。
 *
 * <p>群聊：平台只推送 @ 本机器人的消息，进入编排时 {@code mentionedBot=true}；正文前导 {@code @xxx} 占位剥离。 单聊：{@code chatid}
 * 缺省时用发送者 userid 作为回复目标。
 */
public class WeComEventNormalizer {

  private static final Logger LOG = LoggerFactory.getLogger(WeComEventNormalizer.class);

  static final String CHANNEL_TYPE = "wecom";
  private static final String CHAT_SINGLE = "single";
  private static final String CHAT_GROUP = "group";
  private static final String MSG_TEXT = "text";
  private static final Pattern LEADING_AT = Pattern.compile("^@\\S+\\s*");

  private final String channelName;

  public WeComEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  /** 归一化一条消息回调；结构不完整返回 empty。 */
  public Optional<InboundMessage> normalize(JsonNode body) {
    if (body == null || !body.isObject()) {
      return Optional.empty();
    }
    String msgid = text(body, "msgid");
    String chattype = text(body, "chattype");
    String userid = body.path("from").path("userid").asText(null);
    if (msgid == null || chattype == null || userid == null || userid.isBlank()) {
      LOG.warn("企微消息缺关键字段（msgid/chattype/from.userid），已丢弃");
      return Optional.empty();
    }
    boolean single = CHAT_SINGLE.equals(chattype);
    boolean group = CHAT_GROUP.equals(chattype);
    if (!single && !group) {
      LOG.warn("企微未知 chattype={}，已丢弃", sanitize(chattype));
      return Optional.empty();
    }
    String chatId = single ? userid : text(body, "chatid");
    if (chatId == null || chatId.isBlank()) {
      LOG.warn("企微群消息缺 chatid，已丢弃");
      return Optional.empty();
    }
    String msgtype = text(body, "msgtype");
    boolean textual = MSG_TEXT.equals(msgtype);
    String content = "";
    if (textual) {
      content = body.path("text").path("content").asText("");
      if (group) {
        content = LEADING_AT.matcher(content).replaceFirst("");
      }
      content = content.strip();
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            msgid,
            single ? ChatKind.P2P : ChatKind.GROUP,
            userid,
            chatId,
            content,
            textual,
            group));
  }

  /** 会话类型：1 单聊 / 2 群聊；未知返回 0（发送时让平台兼容解析）。 */
  public static int chatTypeCode(JsonNode body) {
    String chattype = text(body, "chattype");
    if (CHAT_SINGLE.equals(chattype)) {
      return 1;
    }
    if (CHAT_GROUP.equals(chattype)) {
      return 2;
    }
    return 0;
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
