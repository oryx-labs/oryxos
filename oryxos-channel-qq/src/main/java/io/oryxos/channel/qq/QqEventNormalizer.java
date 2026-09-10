package io.oryxos.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * QQ Gateway Dispatch → {@link InboundMessage}。
 *
 * <p>事件：{@code GROUP_AT_MESSAGE_CREATE}、{@code C2C_MESSAGE_CREATE}。附件见 {@code attachments[]}（url +
 * content_type）；语音优先 {@code voice_wav_url}，可选 {@code asr_refer_text}。
 */
public class QqEventNormalizer {

  static final String CHANNEL_TYPE = "qq";
  static final String EVENT_GROUP_AT = "GROUP_AT_MESSAGE_CREATE";
  static final String EVENT_C2C = "C2C_MESSAGE_CREATE";

  private static final Pattern MENTION = Pattern.compile("<@!?\\w+>");
  private static final String FIELD_ID = "id";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_AUTHOR = "author";
  private static final String FIELD_GROUP_OPENID = "group_openid";
  private static final String FIELD_MEMBER_OPENID = "member_openid";
  private static final String FIELD_USER_OPENID = "user_openid";
  private static final String FIELD_ATTACHMENTS = "attachments";
  private static final String FIELD_URL = "url";
  private static final String FIELD_FILENAME = "filename";
  private static final String FIELD_CONTENT_TYPE = "content_type";
  private static final String FIELD_VOICE_WAV_URL = "voice_wav_url";
  private static final String FIELD_ASR_REFER_TEXT = "asr_refer_text";
  private static final String MIME_IMAGE_PREFIX = "image/";
  private static final String MIME_AUDIO = "voice";
  private static final String MIME_AUDIO_PREFIX = "audio/";
  private static final String MIME_VIDEO_PREFIX = "video/";
  private static final String MIME_PDF = "application/pdf";
  private static final String EXT_PDF = ".pdf";
  private static final String EXT_MP4 = ".mp4";
  private static final String EXT_MOV = ".mov";
  private static final String DEFAULT_VOICE_WAV_NAME = "voice.wav";

  private final String channelName;

  public QqEventNormalizer(String channelName) {
    this.channelName = channelName;
  }

  public Optional<InboundMessage> normalize(String eventName, JsonNode data) {
    if (eventName == null || data == null || !data.isObject()) {
      return Optional.empty();
    }
    if (EVENT_GROUP_AT.equals(eventName)) {
      return normalizeGroup(data);
    }
    if (EVENT_C2C.equals(eventName)) {
      return normalizeC2c(data);
    }
    return Optional.empty();
  }

  private Optional<InboundMessage> normalizeGroup(JsonNode data) {
    String messageId = text(data, FIELD_ID);
    String groupOpenid = text(data, FIELD_GROUP_OPENID);
    JsonNode author = data.path(FIELD_AUTHOR);
    String userId = firstNonBlank(text(author, FIELD_MEMBER_OPENID), text(author, FIELD_ID));
    if (messageId == null || groupOpenid == null || userId == null) {
      return Optional.empty();
    }
    AttachmentExtract extracted = extractAttachments(data.path(FIELD_ATTACHMENTS));
    String content = resolveContent(data.path(FIELD_CONTENT).asText(""), extracted.asrReferText());
    if (content.isBlank() && extracted.attachments().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            messageId,
            ChatKind.GROUP,
            userId,
            QqChatTargets.group(groupOpenid),
            content,
            !content.isBlank(),
            true,
            extracted.attachments()));
  }

  private Optional<InboundMessage> normalizeC2c(JsonNode data) {
    String messageId = text(data, FIELD_ID);
    JsonNode author = data.path(FIELD_AUTHOR);
    String userId = firstNonBlank(text(author, FIELD_USER_OPENID), text(author, FIELD_ID));
    if (messageId == null || userId == null) {
      return Optional.empty();
    }
    AttachmentExtract extracted = extractAttachments(data.path(FIELD_ATTACHMENTS));
    String content = resolveContent(data.path(FIELD_CONTENT).asText(""), extracted.asrReferText());
    if (content.isBlank() && extracted.attachments().isEmpty()) {
      return Optional.of(
          new InboundMessage(
              CHANNEL_TYPE,
              channelName,
              messageId,
              ChatKind.P2P,
              userId,
              QqChatTargets.user(userId),
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
            QqChatTargets.user(userId),
            content,
            !content.isBlank(),
            false,
            extracted.attachments()));
  }

  private static String resolveContent(String rawContent, String asrReferText) {
    String content = stripMentions(rawContent == null ? "" : rawContent).strip();
    if (!content.isBlank()) {
      return content;
    }
    if (asrReferText == null || asrReferText.isBlank()) {
      return "";
    }
    return asrReferText.strip();
  }

  /** 附件列表 + 语音平台 ASR 参考文（可拼入正文）。 */
  record AttachmentExtract(List<InboundAttachment> attachments, String asrReferText) {}

  static AttachmentExtract extractAttachments(JsonNode attachments) {
    List<InboundAttachment> out = new ArrayList<>();
    StringBuilder asr = new StringBuilder();
    if (attachments == null || !attachments.isArray()) {
      return new AttachmentExtract(out, null);
    }
    for (JsonNode file : attachments) {
      if (file == null || !file.isObject()) {
        continue;
      }
      String fileName = text(file, FIELD_FILENAME);
      String contentType = text(file, FIELD_CONTENT_TYPE);
      String mime = contentType == null ? "" : asciiLower(contentType);
      boolean voice = MIME_AUDIO.equals(mime) || mime.startsWith(MIME_AUDIO_PREFIX);
      String voiceWav = text(file, FIELD_VOICE_WAV_URL);
      String url = text(file, FIELD_URL);
      // 官方文档：语音优先 WAV（SILK/AMR 原链 ffmpeg 常无法解码）
      if (voice && voiceWav != null) {
        url = voiceWav;
        if (fileName == null || !endsWithIgnoreCase(fileName, ".wav")) {
          fileName = DEFAULT_VOICE_WAV_NAME;
        }
      }
      if (url == null) {
        continue;
      }
      String refer = text(file, FIELD_ASR_REFER_TEXT);
      if (refer != null) {
        if (asr.length() > 0) {
          asr.append(' ');
        }
        asr.append(refer.strip());
      }
      if (mime.startsWith(MIME_IMAGE_PREFIX) || hasImageDimensions(file)) {
        out.add(new InboundAttachment(InboundAttachment.TYPE_IMAGE, url, null, fileName));
      } else if (voice) {
        out.add(new InboundAttachment(InboundAttachment.TYPE_AUDIO, url, null, fileName));
      } else if (mime.startsWith(MIME_VIDEO_PREFIX)
          || endsWithIgnoreCase(fileName, EXT_MP4)
          || endsWithIgnoreCase(fileName, EXT_MOV)) {
        out.add(new InboundAttachment(InboundAttachment.TYPE_VIDEO, url, null, fileName));
      } else if (MIME_PDF.equals(mime) || endsWithIgnoreCase(fileName, EXT_PDF)) {
        out.add(InboundAttachment.fileUrl(url, fileName == null ? "document.pdf" : fileName));
      } else {
        out.add(InboundAttachment.fileUrl(url, fileName));
      }
    }
    return new AttachmentExtract(out, asr.length() == 0 ? null : asr.toString());
  }

  private static boolean hasImageDimensions(JsonNode file) {
    return file.has("width") && file.has("height") && file.path("width").asInt(0) > 0;
  }

  private static boolean endsWithIgnoreCase(String name, String suffix) {
    if (name == null || suffix == null) {
      return false;
    }
    return asciiLower(name).endsWith(asciiLower(suffix));
  }

  private static String asciiLower(String value) {
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  static String stripMentions(String content) {
    if (content == null || content.isBlank()) {
      return content == null ? "" : content;
    }
    return MENTION.matcher(content).replaceAll("").strip();
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

  private static String text(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }
}
