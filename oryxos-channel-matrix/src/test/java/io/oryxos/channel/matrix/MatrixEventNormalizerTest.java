package io.oryxos.channel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatrixEventNormalizerTest {

  private static final String BOT = "@oryx:hs";
  private final ObjectMapper mapper = new ObjectMapper();
  private final MatrixEventNormalizer normalizer = new MatrixEventNormalizer("ops-mx", BOT);

  @Test
  @DisplayName("私聊房间文本 → P2P")
  void directText() {
    var msg = normalizer.normalize("!r:hs", event("hello", false), true);
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.P2P, msg.get().chatKind());
    assertEquals("hello", msg.get().content());
  }

  @Test
  @DisplayName("房间未提及 → 丢弃")
  void roomNoMention() {
    assertTrue(normalizer.normalize("!r:hs", event("hello", false), false).isEmpty());
  }

  @Test
  @DisplayName("房间提及 bot → GROUP")
  void roomMention() {
    var msg = normalizer.normalize("!r:hs", event(BOT + " 查天气", true), false);
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.GROUP, msg.get().chatKind());
    assertEquals("查天气", msg.get().content());
  }

  @Test
  @DisplayName("房间只写 @localpart 也算提及")
  void roomLocalpartMention() {
    var msg = normalizer.normalize("!r:hs", event("@oryx 查天气", false), false);
    assertTrue(msg.isPresent());
    assertEquals("查天气", msg.get().content());
  }

  @Test
  @DisplayName("图片文件名不当正文")
  void imageFilenameNotText() {
    var root = event("shot.png", false);
    ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("content"))
        .put("msgtype", "m.image")
        .put("url", "mxc://hs/img")
        .put("filename", "shot.png");
    var msg = normalizer.normalize("!dm:hs", root, true);
    assertTrue(msg.isPresent());
    assertEquals("", msg.get().content());
    assertEquals(false, msg.get().textual());
    assertEquals("shot.png", msg.get().attachments().get(0).fileName());
  }

  private ObjectNode event(String body, boolean mention) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "m.room.message");
    root.put("sender", "@alice:hs");
    root.put("event_id", "$e1");
    ObjectNode content = root.putObject("content");
    content.put("msgtype", "m.text");
    content.put("body", body);
    if (mention) {
      content.putObject("m.mentions").putArray("user_ids").add(BOT);
    }
    return root;
  }
}
