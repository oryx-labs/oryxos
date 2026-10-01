package io.oryxos.channel.alipay;

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
import java.nio.charset.Charset;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlipayChannelAdapterTest {

  private static final String APP_ID = "2014072300007148";
  private static final Charset GBK = Charset.forName("GBK");

  private ProfileRegistry profiles;
  private InboundMessageService inbound;
  private FakeClient client;
  private AlipayRsa2 rsa;
  private String appPrivateKey;
  private String appPublicKey;
  private String alipayPublicKey;
  private AlipayRsa2 alipaySide; // signs gateway requests as Alipay
  private AlipayChannelAdapter adapter;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    KeyPair app = gen.generateKeyPair();
    KeyPair alipay = gen.generateKeyPair();
    appPrivateKey = Base64.getEncoder().encodeToString(app.getPrivate().getEncoded());
    appPublicKey = Base64.getEncoder().encodeToString(app.getPublic().getEncoded());
    alipayPublicKey = Base64.getEncoder().encodeToString(alipay.getPublic().getEncoded());
    String alipayPrivate = Base64.getEncoder().encodeToString(alipay.getPrivate().getEncoded());

    rsa = new AlipayRsa2(appPrivateKey, alipayPublicKey, appPublicKey);
    // Alipay platform signs with its private key; we verify with alipay public key.
    alipaySide = new AlipayRsa2(alipayPrivate, appPublicKey, alipayPublicKey);

    profiles = mock(ProfileRegistry.class);
    inbound = mock(InboundMessageService.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    client = new FakeClient();
    ChannelConfig config =
        new ChannelConfig(
            "ops-alipay",
            "alipay",
            APP_ID,
            appPrivateKey,
            "demo-agent",
            true,
            Map.of(
                "alipay_public_key", alipayPublicKey,
                "app_public_key", appPublicKey));
    adapter =
        new AlipayChannelAdapter(
            config,
            profiles,
            inbound,
            url -> {},
            Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC),
            client,
            rsa);
    adapter.start();
  }

  @Test
  @DisplayName("verifygw 验签成功回写带应用公钥的 XML")
  void verifygw() {
    String biz =
        "<?xml version=\"1.0\" encoding=\"gbk\"?><XML>"
            + "<AppId><![CDATA["
            + APP_ID
            + "]]></AppId>"
            + "<MsgType><![CDATA[event]]></MsgType>"
            + "<EventType><![CDATA[verifygw]]></EventType>"
            + "</XML>";
    Map<String, String> params = signedGatewayParams(biz);
    WebhookResponse response = adapter.onWebhook(new WebhookRequest("POST", params, Map.of(), ""));
    assertEquals(200, response.status());
    assertTrue(response.body().contains("<success>true</success>"));
    assertTrue(response.body().contains(appPublicKey));
    assertTrue(response.body().contains("<sign>"));
  }

  @Test
  @DisplayName("文本回调 → success + onClaimedMessage")
  void textCallback() {
    String biz =
        "<?xml version=\"1.0\" encoding=\"gbk\"?><XML>"
            + "<AppId><![CDATA["
            + APP_ID
            + "]]></AppId>"
            + "<FromUserId><![CDATA[2088102122458832]]></FromUserId>"
            + "<CreateTime><![CDATA[1403129848]]></CreateTime>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Text><![CDATA[你好]]></Text>"
            + "<MsgId><![CDATA[m1]]></MsgId>"
            + "</XML>";
    Map<String, String> params = signedGatewayParams(biz);
    when(inbound.tryClaim(any(), any())).thenReturn(true);
    WebhookResponse response = adapter.onWebhook(new WebhookRequest("POST", params, Map.of(), ""));
    assertEquals(200, response.status());
    assertEquals("success", response.body());
    verify(inbound).onClaimedMessage(any(InboundMessage.class), any());
  }

  @Test
  @DisplayName("无会话拒绝 sendReply")
  void replyWithoutSession() {
    assertThrows(
        IllegalStateException.class,
        () -> adapter.sendReply(AlipayChatTargets.chatId(APP_ID, "2088"), "hi", null));
  }

  @Test
  @DisplayName("窗内可 sendReply")
  void replyOk() {
    String chatId = AlipayChatTargets.chatId(APP_ID, "2088");
    // remember via text callback path
    String biz =
        "<XML><AppId><![CDATA["
            + APP_ID
            + "]]></AppId>"
            + "<FromUserId><![CDATA[2088]]></FromUserId>"
            + "<MsgType><![CDATA[text]]></MsgType>"
            + "<Text><![CDATA[hi]]></Text>"
            + "<MsgId><![CDATA[m2]]></MsgId></XML>";
    when(inbound.tryClaim(any(), any())).thenReturn(true);
    adapter.onWebhook(new WebhookRequest("POST", signedGatewayParams(biz), Map.of(), ""));
    adapter.sendReply(chatId, "pong", null);
    assertEquals(1, client.sent.get());
  }

  private Map<String, String> signedGatewayParams(String bizContent) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("service", "alipay.service.check");
    params.put("sign_type", AlipayRsa2.SIGN_TYPE);
    params.put("charset", "GBK");
    params.put("biz_content", bizContent);
    // For message callbacks service may differ; still sign the same way.
    if (!bizContent.contains("verifygw")) {
      params.put("service", "alipay.mobile.public.message.notify");
    }
    params.put("sign", alipaySide.sign(params, GBK));
    return params;
  }

  private static final class FakeClient implements AlipayClient {
    final AtomicInteger sent = new AtomicInteger();

    @Override
    public void sendText(String toUserId, String text) {
      sent.incrementAndGet();
    }
  }
}
