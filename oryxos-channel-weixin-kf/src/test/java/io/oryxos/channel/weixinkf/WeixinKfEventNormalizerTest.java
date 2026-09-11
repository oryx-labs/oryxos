package io.oryxos.channel.weixinkf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundMessage;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinKfEventNormalizerTest {

  private final WeixinKfEventNormalizer normalizer = new WeixinKfEventNormalizer("ops-kf");
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("客户文本 → InboundMessage")
  void customerText() {
    ObjectNode item = mapper.createObjectNode();
    item.put("msgid", "m1");
    item.put("open_kfid", "wk1");
    item.put("external_userid", "wu1");
    item.put("origin", 3);
    item.put("msgtype", "text");
    item.putObject("text").put("content", "你好客服");
    Optional<InboundMessage> msg = normalizer.normalize(item);
    assertTrue(msg.isPresent());
    assertEquals("你好客服", msg.get().content());
    assertTrue(msg.get().textual());
    assertEquals("kf:wk1:user:wu1", msg.get().chatId());
  }

  @Test
  @DisplayName("非客户来源丢弃")
  void ignoreSystem() {
    ObjectNode item = mapper.createObjectNode();
    item.put("msgid", "m2");
    item.put("open_kfid", "wk1");
    item.put("external_userid", "wu1");
    item.put("origin", 4);
    item.put("msgtype", "text");
    item.putObject("text").put("content", "sys");
    assertTrue(normalizer.normalize(item).isEmpty());
  }

  @Test
  @DisplayName("非文本 → textual=false")
  void nonText() {
    ObjectNode item = mapper.createObjectNode();
    item.put("msgid", "m3");
    item.put("open_kfid", "wk1");
    item.put("external_userid", "wu1");
    item.put("origin", 3);
    item.put("msgtype", "image");
    Optional<InboundMessage> msg = normalizer.normalize(item);
    assertTrue(msg.isPresent());
    assertFalse(msg.get().textual());
  }
}
