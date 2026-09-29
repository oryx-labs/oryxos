package io.oryxos.channel.mattermost;

import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Mattermost Outgoing Webhook 表单 → {@link InboundMessage}。频道需 trigger_word 或 {@code @bot}。 */
public class MattermostEventNormalizer {

  static final String CHANNEL_TYPE = "mattermost";
  private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9._-]+\\s*");
  private static final String FIELD_POST_ID = "post_id";
  private static final String FIELD_ID = "id";
  private static final String FIELD_USER_ID = "user_id";
  private static final String FIELD_USER_NAME = "user_name";
  private static final String FIELD_CHANNEL_ID = "channel_id";
  private static final String FIELD_CHANNEL_NAME = "channel_name";
  private static final String FIELD_TEXT = "text";
  private static final String FIELD_TRIGGER_WORD = "trigger_word";
  private static final String FIELD_CHANNEL_TYPE = "channel_type";
  private static final String FIELD_TOKEN = "token";
  private static final String CHANNEL_TYPE_DM = "D";
  private static final String AT_PREFIX = "@";
  private static final String FORM_PAIR_SEPARATOR = "&";
  private static final char FORM_KV_SEPARATOR = '=';

  private final String channelName;
  private final String botUsername;

  public MattermostEventNormalizer(String channelName, String botUsername) {
    this.channelName = channelName;
    this.botUsername = botUsername == null ? "" : botUsername.strip();
  }

  public Optional<InboundMessage> normalize(String formBody) {
    Map<String, String> form = parseForm(formBody);
    String messageId = firstNonBlank(form.get(FIELD_POST_ID), form.get(FIELD_ID));
    String userId = firstNonBlank(form.get(FIELD_USER_ID), form.get(FIELD_USER_NAME));
    String chatId = firstNonBlank(form.get(FIELD_CHANNEL_ID), form.get(FIELD_CHANNEL_NAME));
    if (messageId == null || userId == null || chatId == null) {
      return Optional.empty();
    }
    String text = form.getOrDefault(FIELD_TEXT, "").strip();
    String trigger = form.getOrDefault(FIELD_TRIGGER_WORD, "");
    String channelType = form.getOrDefault(FIELD_CHANNEL_TYPE, "");
    boolean dm =
        asciiLower(CHANNEL_TYPE_DM).equals(asciiLower(channelType))
            || form.getOrDefault(FIELD_CHANNEL_NAME, "").startsWith(AT_PREFIX);
    if (!dm) {
      if (trigger.isBlank() && !mentionsBot(text)) {
        return Optional.empty();
      }
      text = stripMention(text);
      if (text.isBlank()) {
        // 只 @Bot / 配图无说明：先放行，适配器再按 post 拉 file_ids；无附件则不进编排
        return Optional.of(
            new InboundMessage(
                CHANNEL_TYPE,
                channelName,
                messageId,
                ChatKind.GROUP,
                userId,
                chatId,
                "",
                false,
                true,
                List.of()));
      }
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.GROUP,
              userId,
              chatId,
              text,
              true,
              true,
              List.of()));
    }
    if (text.isBlank()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.P2P,
              userId,
              chatId,
              "",
              false,
              false,
              List.of()));
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.P2P,
            userId,
            chatId,
            text,
            true,
            false,
            List.of()));
  }

  boolean tokenMatches(String formBody, String expected) {
    if (expected == null || expected.isBlank()) {
      return false;
    }
    return expected.equals(parseForm(formBody).get(FIELD_TOKEN));
  }

  private boolean mentionsBot(String text) {
    if (botUsername.isBlank() || text == null) {
      return false;
    }
    return asciiLower(text).contains(AT_PREFIX + asciiLower(botUsername));
  }

  private String stripMention(String text) {
    return text == null ? "" : MENTION.matcher(text).replaceAll("").strip();
  }

  static Map<String, String> parseForm(String body) {
    Map<String, String> out = new LinkedHashMap<>();
    if (body == null || body.isBlank()) {
      return out;
    }
    for (String pair : body.split(FORM_PAIR_SEPARATOR)) {
      int eq = pair.indexOf(FORM_KV_SEPARATOR);
      if (eq <= 0) {
        continue;
      }
      String key = urlDecode(pair.substring(0, eq));
      String value = urlDecode(pair.substring(eq + 1));
      out.put(key, value);
    }
    return out;
  }

  private static String asciiLower(String value) {
    if (value == null) {
      return "";
    }
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  private static String urlDecode(String value) {
    return URLDecoder.decode(value.replace('+', ' '), StandardCharsets.UTF_8);
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
  }
}
