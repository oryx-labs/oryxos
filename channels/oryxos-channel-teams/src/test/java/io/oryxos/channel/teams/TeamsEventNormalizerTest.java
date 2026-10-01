package io.oryxos.channel.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamsEventNormalizerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final TeamsEventNormalizer normalizer = new TeamsEventNormalizer("ops-teams", "app-1");

  @Test
  @DisplayName("1:1 personal → P2P")
  void personal() {
    Optional<InboundMessage> msg = normalizer.normalize(activity("personal", "hello", false));
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.P2P, msg.get().chatKind());
    assertEquals("hello", msg.get().content());
  }

  @Test
  @DisplayName("频道未 @ → 丢弃")
  void channelNoMention() {
    assertTrue(normalizer.normalize(activity("channel", "hello", false)).isEmpty());
  }

  @Test
  @DisplayName("频道 @Bot → GROUP")
  void channelMention() {
    Optional<InboundMessage> msg =
        normalizer.normalize(activity("channel", "<at>Bot</at> 查天气", true));
    assertTrue(msg.isPresent());
    assertEquals(ChatKind.GROUP, msg.get().chatKind());
    assertEquals("查天气", msg.get().content());
  }

  private ObjectNode activity(String convType, String text, boolean mention) {
    ObjectNode root = mapper.createObjectNode();
    root.put("type", "message");
    root.put("id", "m1");
    root.put("text", text);
    root.put("serviceUrl", "https://smba.trafficmanager.net/amer/");
    root.putObject("from").put("id", "29:user");
    root.putObject("conversation").put("id", "19:chat").put("conversationType", convType);
    if (mention) {
      root.putArray("entities")
          .addObject()
          .put("type", "mention")
          .putObject("mentioned")
          .put("id", "28:app-1");
    }
    return root;
  }
}
