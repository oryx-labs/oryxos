package io.oryxos.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.ArrayList;
import java.util.List;
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
  private static final String MSG_IMAGE = "image";
  private static final String MSG_FILE = "file";
  private static final String MSG_VOICE = "voice";
  private static final String MSG_VIDEO = "video";
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
    Payload payload = parsePayload(body, msgid, group);
    return Optional.of(
        new InboundMessage(
            CHANNEL_TYPE,
            channelName,
            msgid,
            single ? ChatKind.P2P : ChatKind.GROUP,
            userid,
            chatId,
            payload.content,
            payload.textual,
            group,
            payload.attachments));
  }

  private static Payload parsePayload(JsonNode body, String msgid, boolean group) {
    String msgtype = text(body, "msgtype");
    boolean textual = MSG_TEXT.equals(msgtype);
    String content = "";
    List<InboundAttachment> attachments = new ArrayList<>();
    if (textual) {
      content = body.path("text").path("content").asText("");
      if (group) {
        content = LEADING_AT.matcher(content).replaceFirst("");
      }
      content = content.strip();
    } else if (MSG_VOICE.equals(msgtype)) {
      JsonNode voice = body.path("voice");
      content = voice.path("content").asText("");
      content = content == null ? "" : content.strip();
      if (!content.isBlank()) {
        textual = true;
        content = "[语音转写] " + content;
      } else {
        Optional<InboundAttachment> audio = extractVoice(voice);
        if (audio.isPresent()) {
          attachments.add(audio.get());
        } else {
          textual = true;
          content = "企微仅单聊提供 ASR；空转写请改发文字";
        }
      }
    } else if (MSG_IMAGE.equals(msgtype)) {
      extractImage(body.path("image")).ifPresent(attachments::add);
    } else if (MSG_FILE.equals(msgtype)) {
      extractFile(body.path("file")).ifPresent(attachments::add);
    } else if (MSG_VIDEO.equals(msgtype)) {
      extractVideo(body.path("video")).ifPresent(attachments::add);
    } else if (msgtype != null && !msgtype.isBlank()) {
      LOG.info("企微收到暂不支持的消息类型 msgtype={} msgid={}", sanitize(msgtype), sanitize(msgid));
    }
    return new Payload(content, textual, attachments);
  }

  private static Optional<InboundAttachment> extractImage(JsonNode image) {
    String imageUrl = image.path("url").asText(null);
    if (imageUrl == null || imageUrl.isBlank()) {
      return Optional.empty();
    }
    String aesKey = image.path("aeskey").asText(null);
    if (aesKey != null && !aesKey.isBlank()) {
      return Optional.of(new InboundAttachment(InboundAttachment.TYPE_IMAGE, imageUrl, aesKey));
    }
    return Optional.of(InboundAttachment.imageUrl(imageUrl));
  }

  private static Optional<InboundAttachment> extractFile(JsonNode file) {
    String fileUrl = file.path("url").asText(null);
    if (fileUrl == null || fileUrl.isBlank()) {
      return Optional.empty();
    }
    String aesKey = file.path("aeskey").asText(null);
    String fileName = file.path("file_name").asText(null);
    if (fileName == null || fileName.isBlank()) {
      fileName = file.path("filename").asText(null);
    }
    if (aesKey != null && !aesKey.isBlank()) {
      return Optional.of(
          new InboundAttachment(InboundAttachment.TYPE_FILE, fileUrl, aesKey, fileName));
    }
    return Optional.of(InboundAttachment.fileUrl(fileUrl, fileName));
  }

  /** 空 ASR 时若有临时 URL，按文件同款带可选 aeskey 落 TYPE_AUDIO，供本地下载/转写。 */
  private static Optional<InboundAttachment> extractVoice(JsonNode voice) {
    String voiceUrl = voice.path("url").asText(null);
    if (voiceUrl == null || voiceUrl.isBlank()) {
      return Optional.empty();
    }
    String aesKey = voice.path("aeskey").asText(null);
    if (aesKey != null && !aesKey.isBlank()) {
      return Optional.of(new InboundAttachment(InboundAttachment.TYPE_AUDIO, voiceUrl, aesKey));
    }
    return Optional.of(InboundAttachment.audioUrl(voiceUrl));
  }

  private static Optional<InboundAttachment> extractVideo(JsonNode video) {
    String videoUrl = video.path("url").asText(null);
    if (videoUrl == null || videoUrl.isBlank()) {
      return Optional.empty();
    }
    String aesKey = video.path("aeskey").asText(null);
    if (aesKey != null && !aesKey.isBlank()) {
      return Optional.of(new InboundAttachment(InboundAttachment.TYPE_VIDEO, videoUrl, aesKey));
    }
    return Optional.of(InboundAttachment.videoUrl(videoUrl));
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

  private static final class Payload {
    private final String content;
    private final boolean textual;
    private final List<InboundAttachment> attachments;

    private Payload(String content, boolean textual, List<InboundAttachment> attachments) {
      this.content = content;
      this.textual = textual;
      this.attachments = attachments;
    }
  }
}
