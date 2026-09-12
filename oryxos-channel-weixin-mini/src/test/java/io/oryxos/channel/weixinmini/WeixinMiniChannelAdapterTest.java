package io.oryxos.channel.weixinmini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.channel.ChannelConfig;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinMiniChannelAdapterTest {

  private static final String TOKEN = "QDG6eK";
  private static final String APP_ID = "wx5823bf96d3bd56c7";
  private static final String AES_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

  private ProfileRegistry profiles;
  private InboundMessageService inbound;
  private FakeClient client;
  private WeixinMiniMsgCrypt crypt;
  private WeixinMiniChannelAdapter adapter;

  @BeforeEach
  void setUp() {
    profiles = mock(ProfileRegistry.class);
    inbound = mock(InboundMessageService.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    client = new FakeClient();
    crypt = new WeixinMiniMsgCrypt(TOKEN, AES_KEY, APP_ID);
    ChannelConfig config =
        new ChannelConfig(
            "ops-mini",
            "weixin_mini",
            APP_ID,
            "secret",
            "demo-agent",
            true,
            Map.of(
                "token", TOKEN,
                "encoding_aes_key", AES_KEY));
    adapter =
        new WeixinMiniChannelAdapter(
            config,
            profiles,
            inbound,
            url -> {},
            Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC),
            client,
            crypt);
    adapter.start();
  }

  @Test
  @DisplayName("GET URL 明文验签原样回 echostr")
  void urlVerify() throws Exception {
    String echoPlain = "hello_echo";
    String ts = "1409659813";
    String nonce = "1372623149";
    String sig = crypt.plainSignature(ts, nonce);
    WebhookResponse response =
        adapter.onWebhook(
            new WebhookRequest(
                "GET",
                Map.of(
                    "signature", sig,
                    "timestamp", ts,
                    "nonce", nonce,
                    "echostr", echoPlain),
                Map.of(),
                ""));
    assertEquals(200, response.status());
    assertEquals(echoPlain, response.body());
  }

  @Test
  @DisplayName("POST 加密文本 → success + onClaimedMessage")
  void callbackText() throws Exception {
    String textXml =
        "<xml>"
            + "<ToUserName><![CDATA[gh_xxx]]></ToUserName>"
            + "<FromUserName><![CDATA[OPENID1]]></FromUserName>"
            + "<CreateTime>1348831860</CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Content><![CDATA[你好小程序]]></Content>"
            + "<MsgId>1234567890123456</MsgId>"
            + "</xml>";
    String cipher = crypt.encrypt(textXml);
    String ts = "1409659813";
    String nonce = "1372623149";
    String sig = crypt.signature(ts, nonce, cipher);
    String body = "<xml><Encrypt><![CDATA[" + cipher + "]]></Encrypt></xml>";
    when(inbound.tryClaim(any(), any())).thenReturn(true);
    WebhookResponse response =
        adapter.onWebhook(
            new WebhookRequest(
                "POST",
                Map.of(
                    "msg_signature", sig,
                    "timestamp", ts,
                    "nonce", nonce),
                Map.of(),
                body));
    assertEquals(200, response.status());
    assertEquals("success", response.body());
    verify(inbound).onClaimedMessage(any(InboundMessage.class), any());
  }

  @Test
  @DisplayName("POST 事件消息忽略但仍 success")
  void callbackEventIgnored() throws Exception {
    String eventXml =
        "<xml>"
            + "<ToUserName><![CDATA["
            + APP_ID
            + "]]></ToUserName>"
            + "<FromUserName><![CDATA[OPENID2]]></FromUserName>"
            + "<CreateTime>1348831860</CreateTime>"
            + "<MsgType><![CDATA[event]]></MsgType>"
            + "<Event><![CDATA[user_enter_tempsession]]></Event>"
            + "</xml>";
    String cipher = crypt.encrypt(eventXml);
    String ts = "1409659813";
    String nonce = "1372623149";
    String sig = crypt.signature(ts, nonce, cipher);
    String body = "<xml><Encrypt><![CDATA[" + cipher + "]]></Encrypt></xml>";
    WebhookResponse response =
        adapter.onWebhook(
            new WebhookRequest(
                "POST",
                Map.of(
                    "msg_signature", sig,
                    "timestamp", ts,
                    "nonce", nonce),
                Map.of(),
                body));
    assertEquals(200, response.status());
    assertEquals("success", response.body());
  }

  @Test
  @DisplayName("无会话 sendReply 硬拒绝")
  void sendWithoutSession() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> adapter.sendReply("mini:" + APP_ID + ":user:OPENID1", "hi", null));
    assertTrue(e.getMessage().contains("会话"));
  }

  @Test
  @DisplayName("窗内可回复并计数")
  void sendWithinWindow() throws Exception {
    callbackText();
    adapter.sendReply("mini:" + APP_ID + ":user:OPENID1", "回复", null);
    assertEquals(1, client.sendCalls.get());
  }

  private static final class FakeClient implements WeixinMiniClient {
    final AtomicInteger sendCalls = new AtomicInteger();

    @Override
    public void sendText(String openId, String text) {
      sendCalls.incrementAndGet();
    }
  }
}
