package io.oryxos.channel.weixinkf;

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
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeixinKfChannelAdapterTest {

  private static final String TOKEN = "QDG6eK";
  private static final String CORP_ID = "wx5823bf96d3bd56c7";
  private static final String AES_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
  private static final String OPEN_KFID = "wkOPEN";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProfileRegistry profiles;
  private InboundMessageService inbound;
  private FakeClient client;
  private WeixinKfMsgCrypt crypt;
  private WeixinKfChannelAdapter adapter;

  @BeforeEach
  void setUp() {
    profiles = mock(ProfileRegistry.class);
    inbound = mock(InboundMessageService.class);
    when(profiles.get("demo-agent")).thenReturn(Optional.of(mock(Profile.class)));
    client = new FakeClient();
    crypt = new WeixinKfMsgCrypt(TOKEN, AES_KEY, CORP_ID);
    ChannelConfig config =
        new ChannelConfig(
            "ops-kf",
            "weixin_kf",
            CORP_ID,
            "secret",
            "demo-agent",
            true,
            Map.of(
                "token", TOKEN,
                "encoding_aes_key", AES_KEY,
                "open_kfid", OPEN_KFID));
    adapter =
        new WeixinKfChannelAdapter(
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
  @DisplayName("GET URL 校验回写明文 echostr")
  void urlVerify() throws Exception {
    String echoPlain = "hello_echo";
    String cipher = crypt.encrypt(echoPlain);
    String ts = "1409659813";
    String nonce = "1372623149";
    String sig = crypt.signature(ts, nonce, cipher);
    WebhookResponse response =
        adapter.onWebhook(
            new WebhookRequest(
                "GET",
                Map.of(
                    "msg_signature", sig,
                    "timestamp", ts,
                    "nonce", nonce,
                    "echostr", cipher),
                Map.of(),
                ""));
    assertEquals(200, response.status());
    assertEquals(echoPlain, response.body());
  }

  @Test
  @DisplayName("POST kf_msg_or_event → sync → onMessage")
  void callbackSync() throws Exception {
    ObjectNode item = MAPPER.createObjectNode();
    item.put("msgid", "msg-1");
    item.put("open_kfid", OPEN_KFID);
    item.put("external_userid", "wu1");
    item.put("origin", 3);
    item.put("msgtype", "text");
    item.putObject("text").put("content", "你好客服");
    client.messages = List.of(item);

    String eventXml =
        "<xml><ToUserName><![CDATA["
            + CORP_ID
            + "]]></ToUserName>"
            + "<CreateTime>1348831860</CreateTime>"
            + "<MsgType><![CDATA[event]]></MsgType>"
            + "<Event><![CDATA[kf_msg_or_event]]></Event>"
            + "<Token><![CDATA[ENC_TOKEN]]></Token>"
            + "<OpenKfId><![CDATA["
            + OPEN_KFID
            + "]]></OpenKfId></xml>";
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
    verify(inbound).onMessage(any(InboundMessage.class), any());
    assertEquals(1, client.ensureCalls.get());
  }

  @Test
  @DisplayName("无会话 sendReply 硬拒绝")
  void sendWithoutSession() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> adapter.sendReply("kf:" + OPEN_KFID + ":user:wu1", "hi", null));
    assertTrue(e.getMessage().contains("会话"));
  }

  @Test
  @DisplayName("窗内可回复并计数")
  void sendWithinWindow() throws Exception {
    callbackSync();
    adapter.sendReply("kf:" + OPEN_KFID + ":user:wu1", "回复", null);
    assertEquals(1, client.sendCalls.get());
  }

  private static final class FakeClient implements WeixinKfClient {
    List<com.fasterxml.jackson.databind.JsonNode> messages = List.of();
    final AtomicInteger ensureCalls = new AtomicInteger();
    final AtomicInteger sendCalls = new AtomicInteger();

    @Override
    public WeixinKfSyncResult syncMsg(String openKfid, String callbackToken, String cursor) {
      return new WeixinKfSyncResult(new ArrayList<>(messages), "next", false);
    }

    @Override
    public void sendText(String openKfid, String externalUserId, String text) {
      sendCalls.incrementAndGet();
    }

    @Override
    public void ensureAiReception(String openKfid, String externalUserId) {
      ensureCalls.incrementAndGet();
    }
  }
}
