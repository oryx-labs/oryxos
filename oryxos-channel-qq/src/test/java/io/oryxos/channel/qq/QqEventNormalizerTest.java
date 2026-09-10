package io.oryxos.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QqEventNormalizerTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private QqEventNormalizer normalizer;

  @BeforeEach
  void setUp() {
    normalizer = new QqEventNormalizer("ops-qq");
  }

  @Test
  @DisplayName("群 @Bot 文本 → GROUP，剥离 mention")
  void groupAtMessage() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-g1");
    data.put("group_openid", "g-open");
    data.put("content", "<@!12345> 查天气");
    data.putObject("author").put("member_openid", "m-open");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_GROUP_AT, data);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.GROUP, m.chatKind());
    assertTrue(m.mentionedBot());
    assertEquals("查天气", m.content());
    assertEquals("group:g-open", m.chatId());
    assertEquals("m-open", m.userId());
  }

  @Test
  @DisplayName("单聊文本 → P2P")
  void c2cText() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-c1");
    data.put("content", "hello");
    data.putObject("author").put("user_openid", "u-open");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    InboundMessage m = msg.get();
    assertEquals(ChatKind.P2P, m.chatKind());
    assertFalse(m.mentionedBot());
    assertEquals("hello", m.content());
    assertEquals("user:u-open", m.chatId());
  }

  @Test
  @DisplayName("单聊空内容无附件 → 非文本占位")
  void c2cNonTextual() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-c2");
    data.put("content", "");
    data.putObject("author").put("user_openid", "u-open");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    assertFalse(msg.get().textual());
    assertTrue(msg.get().attachments().isEmpty());
  }

  @Test
  @DisplayName("单聊图片附件 → TYPE_IMAGE")
  void c2cImageAttachment() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-img");
    data.put("content", "");
    data.putObject("author").put("user_openid", "u-open");
    data.putArray("attachments")
        .addObject()
        .put("url", "https://multimedia.nt.qq.com.cn/download?x=1")
        .put("filename", "photo.jpg")
        .put("content_type", "image/jpeg")
        .put("width", 800)
        .put("height", 600);
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    assertEquals(1, msg.get().attachments().size());
    assertEquals(InboundAttachment.TYPE_IMAGE, msg.get().attachments().get(0).type());
    assertTrue(msg.get().attachments().get(0).url().contains("multimedia.nt.qq.com.cn"));
  }

  @Test
  @DisplayName("单聊 PDF → TYPE_FILE")
  void c2cPdfAttachment() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-pdf");
    data.put("content", "看看文档");
    data.putObject("author").put("user_openid", "u-open");
    data.putArray("attachments")
        .addObject()
        .put("url", "https://multimedia.nt.qq.com.cn/download?f=pdf")
        .put("filename", "report.pdf")
        .put("content_type", "application/pdf");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    assertEquals(InboundAttachment.TYPE_FILE, msg.get().attachments().get(0).type());
    assertEquals("report.pdf", msg.get().attachments().get(0).fileName());
  }

  @Test
  @DisplayName("群仅附件无正文 → 保留")
  void groupAttachmentOnly() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-g-img");
    data.put("group_openid", "g-open");
    data.put("content", "<@!1>");
    data.putObject("author").put("member_openid", "m-open");
    data.putArray("attachments")
        .addObject()
        .put("url", "https://multimedia.nt.qq.com.cn/download?x=2")
        .put("content_type", "image/png")
        .put("filename", "a.png")
        .put("width", 10)
        .put("height", 10);
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_GROUP_AT, data);
    assertTrue(msg.isPresent());
    assertEquals(1, msg.get().attachments().size());
  }

  @Test
  @DisplayName("单聊语音优先 voice_wav_url，并采用 asr_refer_text")
  void c2cVoicePrefersWavAndAsr() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-voice");
    data.put("content", "");
    data.putObject("author").put("user_openid", "u-open");
    data.putArray("attachments")
        .addObject()
        .put("url", "https://multimedia.nt.qq.com.cn/download?silk=1")
        .put("voice_wav_url", "https://multimedia.nt.qq.com.cn/download?wav=1")
        .put("asr_refer_text", "今天天气不错")
        .put("filename", "voice.amr")
        .put("content_type", "voice");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    assertEquals("今天天气不错", msg.get().content());
    assertEquals(1, msg.get().attachments().size());
    InboundAttachment a = msg.get().attachments().get(0);
    assertEquals(InboundAttachment.TYPE_AUDIO, a.type());
    assertTrue(a.url().contains("wav=1"));
    assertEquals("voice.wav", a.fileName());
  }

  @Test
  @DisplayName("单聊 video/mp4 → TYPE_VIDEO")
  void c2cVideoAttachment() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-vid");
    data.put("content", "");
    data.putObject("author").put("user_openid", "u-open");
    data.putArray("attachments")
        .addObject()
        .put("url", "https://multimedia.nt.qq.com.cn/download?v=1")
        .put("filename", "clip.mp4")
        .put("content_type", "video/mp4");
    Optional<InboundMessage> msg = normalizer.normalize(QqEventNormalizer.EVENT_C2C, data);
    assertTrue(msg.isPresent());
    assertEquals(InboundAttachment.TYPE_VIDEO, msg.get().attachments().get(0).type());
  }

  @Test
  @DisplayName("频道等非 MVP 事件 → 丢弃")
  void guildEventDropped() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-x");
    data.put("content", "hi");
    assertTrue(normalizer.normalize("AT_MESSAGE_CREATE", data).isEmpty());
  }

  @Test
  @DisplayName("群空内容无附件 → 丢弃")
  void groupBlankDropped() {
    ObjectNode data = MAPPER.createObjectNode();
    data.put("id", "msg-g2");
    data.put("group_openid", "g-open");
    data.put("content", "<@!1>   ");
    data.putObject("author").put("member_openid", "m-open");
    assertTrue(normalizer.normalize(QqEventNormalizer.EVENT_GROUP_AT, data).isEmpty());
  }
}
