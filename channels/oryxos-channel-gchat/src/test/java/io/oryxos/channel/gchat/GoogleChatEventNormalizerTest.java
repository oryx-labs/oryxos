package io.oryxos.channel.gchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GoogleChatEventNormalizerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final GoogleChatEventNormalizer normalizer = new GoogleChatEventNormalizer("ops-gchat");

  @Test
  @DisplayName("DM → P2P")
  void dm() {
    var msg = normalizer.normalize(event("DM", "hello", null));
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.P2P, msg.get().chatKind());
    assertEquals("hello", msg.get().content());
  }

  @Test
  @DisplayName("空间无 argumentText → 丢弃")
  void roomNoMention() {
    assertTrue(normalizer.normalize(event("ROOM", "@Bot hello", null)).isEmpty());
  }

  @Test
  @DisplayName("空间 argumentText → GROUP")
  void roomMention() {
    var msg = normalizer.normalize(event("ROOM", "@Bot 查天气", " 查天气"));
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.GROUP, msg.get().chatKind());
    assertEquals("查天气", msg.get().content());
  }

  private ObjectNode event(String spaceType, String text, String argumentText) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "MESSAGE");
    ObjectNode message = root.putObject("message");
    message.put("name", "spaces/AAA/messages/BBB");
    message.put("text", text);
    if (argumentText != null) {
      message.put("argumentText", argumentText);
    }
    message.putObject("sender").put("name", "users/1");
    message.putObject("space").put("name", "spaces/AAA").put("type", spaceType);
    return root;
  }
}
