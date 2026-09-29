package io.oryxos.channel.whatsapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WhatsAppChannelAdapterTest {

  private static final String SECRET = "app-secret";
  private static final String BODY =
      "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"from\":\"16315551181\",\"id\":\"wamid.1\",\"timestamp\":\"1700000000\",\"type\":\"text\",\"text\":{\"body\":\"hi\"}}]}}]}]}";

  private ProfileRegistry profiles;
  private InboundMessageService inbound;
  private WhatsAppChannelAdapter adapter;

  @BeforeEach
  void setUp() {
    profiles = mock(ProfileRegistry.class);
    inbound = mock(InboundMessageService.class);
    when(profiles.get("ops-agent")).thenReturn(Optional.of(mock(Profile.class)));
    ChannelConfig config =
        new ChannelConfig(
            "ops-wa",
            "whatsapp",
            "token",
            SECRET,
            "ops-agent",
            true,
            Map.of("verify_token", "verify", "phone_number_id", "123"));
    adapter =
        new WhatsAppChannelAdapter(
            config,
            profiles,
            inbound,
            url -> {},
            Clock.fixed(Instant.parse("2023-11-15T00:00:00Z"), ZoneOffset.UTC));
    adapter.start();
  }

  @Test
  @DisplayName("GET 订阅挑战回写 hub.challenge")
  void challenge() {
    WebhookResponse resp =
        adapter.onWebhook(
            new WebhookRequest(
                "GET",
                Map.of(
                    "hub.mode", "subscribe", "hub.verify_token", "verify", "hub.challenge", "abc"),
                Map.of(),
                ""));
    assertEquals(200, resp.status());
    assertEquals("abc", resp.body());
  }

  @Test
  @DisplayName("验签通过后入站编排")
  void signedInbound() throws Exception {
    WebhookResponse resp =
        adapter.onWebhook(
            new WebhookRequest("POST", Map.of(), Map.of("x-hub-signature-256", sign(BODY)), BODY));
    assertEquals(200, resp.status());
    verify(inbound).onMessage(any(InboundMessage.class), any());
    assertEquals(ChannelStatus.State.CONNECTED, adapter.status().state());
  }

  @Test
  @DisplayName("窗外 sendReply 硬拒绝")
  void windowExpiredHardReject() {
    adapter.rememberInbound("16315551181", Instant.parse("2023-11-13T00:00:00Z").toEpochMilli());
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> adapter.sendReply("16315551181", "hi", null));
    assertTrue(e.getMessage().contains("24"));
  }

  @Test
  @DisplayName("从未入站过的会话 sendReply 同样硬拒绝")
  void neverInboundHardReject() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> adapter.sendReply("16315551181", "hi", null));
    assertTrue(e.getMessage().contains("24"));
  }

  private static String sign(String body) throws GeneralSecurityException {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }
}
