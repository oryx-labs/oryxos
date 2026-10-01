package io.oryxos.channel.douyin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DouyinChannelAdapterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SECRET = "secret";
  private static final String OPERATOR = "op-open";

  private ProfileRegistry profiles;
  private InboundMessageService inbound;
  private DouyinChannelAdapter adapter;
  private String inboundBody;

  @BeforeEach
  void setUp() throws Exception {
    profiles = mock(ProfileRegistry.class);
    inbound = mock(InboundMessageService.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    ChannelConfig config =
        new ChannelConfig(
            "ops-douyin",
            "douyin",
            "ck",
            SECRET,
            "demo-agent",
            true,
            Map.of("open_id", OPERATOR, "access_token", "tok"));
    adapter =
        new DouyinChannelAdapter(
            config,
            profiles,
            inbound,
            url -> {},
            Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC));
    adapter.start();
    inboundBody = sampleInboundBody();
  }

  @Test
  @DisplayName("verify_webhook 回写 challenge JSON")
  void verifyChallenge() {
    String body =
        "{\"event\":\"verify_webhook\",\"client_key\":\"ck\",\"content\":{\"challenge\":12345}}";
    WebhookResponse response =
        adapter.onWebhook(new WebhookRequest("POST", Map.of(), Map.of(), body));
    assertEquals(200, response.status());
    assertTrue(response.contentType().contains("json"));
    assertTrue(response.body().contains("12345"));
  }

  @Test
  @DisplayName("签名错误 → 401")
  void badSignature() {
    WebhookResponse response =
        adapter.onWebhook(
            new WebhookRequest(
                "POST", Map.of(), Map.of("x-douyin-signature", "deadbeef"), inboundBody));
    assertEquals(401, response.status());
  }

  @Test
  @DisplayName("合法签名文本入站 → onMessage")
  void inboundOk() {
    String sig = DouyinWebhookSignature.sha1Hex(SECRET + inboundBody);
    WebhookResponse response =
        adapter.onWebhook(
            new WebhookRequest("POST", Map.of(), Map.of("x-douyin-signature", sig), inboundBody));
    assertEquals(200, response.status());
    verify(inbound).onMessage(any(InboundMessage.class), any());
    assertEquals(ChannelStatus.State.CONNECTED, adapter.status().state());
  }

  @Test
  @DisplayName("无会话上下文 sendReply 硬拒绝")
  void sendWithoutSession() {
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> adapter.sendReply("user:u1", "hi", null));
    assertTrue(e.getMessage().contains("会话"));
  }

  private static String sampleInboundBody() throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("event", "im_receive_msg");
    root.put("from_user_id", "u1");
    root.put("to_user_id", OPERATOR);
    root.put("client_key", "ck");
    ObjectNode content = root.putObject("content");
    content.put("conversation_short_id", "conv-1");
    content.put("server_message_id", "msg-1");
    content.put("conversation_type", 1);
    content.put("message_type", "text");
    content.put("text", "你好抖音");
    return MAPPER.writeValueAsString(root);
  }
}
