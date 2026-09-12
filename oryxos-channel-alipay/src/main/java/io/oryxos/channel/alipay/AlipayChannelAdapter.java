package io.oryxos.channel.alipay;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.charset.Charset;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 支付宝生活号：应用网关（RSA2 验签 / verifygw）→ 文本 → Agent → {@code alipay.open.public.message.custom.send}。
 *
 * <p>{@code app_id}=应用 AppId；{@code app_secret}=应用 PKCS#8 私钥；{@code
 * extra.alipay_public_key}=支付宝公钥；{@code extra.app_public_key}=应用公钥（verifygw 回执）。
 */
public class AlipayChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "alipay";

  private static final Logger log = LoggerFactory.getLogger(AlipayChannelAdapter.class);
  private static final String EXTRA_ALIPAY_PUBLIC_KEY = "alipay_public_key";
  private static final String EXTRA_APP_PUBLIC_KEY = "app_public_key";
  private static final String SERVICE_CHECK = "alipay.service.check";
  private static final String EVENT_VERIFYGW = "verifygw";
  private static final String MSG_EVENT = "event";
  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNAUTHORIZED = 401;
  private static final String ACK_SUCCESS = "success";
  private static final Charset GATEWAY_CHARSET = Charset.forName("GBK");

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Clock clock;
  private final AlipayReplySessionStore sessions = new AlipayReplySessionStore();

  private volatile AlipayRsa2 rsa;
  private volatile AlipayClient api;
  private volatile AlipayEventNormalizer normalizer;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  public AlipayChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, Clock.systemUTC());
  }

  AlipayChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      Clock clock) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
    this.clock = clock;
  }

  /** 测试注入。 */
  AlipayChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      Clock clock,
      AlipayClient api,
      AlipayRsa2 rsa) {
    this(config, profileRegistry, inboundMessageService, guard, clock);
    this.api = api;
    this.rsa = rsa;
  }

  @Override
  public String name() {
    return config.name();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public String boundAgent() {
    return config.agent();
  }

  @Override
  public synchronized void start() {
    config.validateCredentialsResolved();
    requireExtra(EXTRA_ALIPAY_PUBLIC_KEY);
    requireExtra(EXTRA_APP_PUBLIC_KEY);
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(AlipayApiClient.API_BASE);
    if (rsa == null) {
      rsa =
          new AlipayRsa2(
              config.appSecret(),
              config.extra(EXTRA_ALIPAY_PUBLIC_KEY),
              config.extra(EXTRA_APP_PUBLIC_KEY));
    }
    if (api == null) {
      api = new AlipayApiClient(guard, config.appId(), rsa);
    }
    normalizer = new AlipayEventNormalizer(config.name(), config.appId());
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
    normalizer = null;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    AlipayClient client = api;
    if (client == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    sessions.requireForReply(chatId, clock.millis());
    AlipayChatTargets.Parsed target = AlipayChatTargets.parse(chatId);
    String configuredAppId = config.appId();
    if (configuredAppId != null
        && !configuredAppId.isBlank()
        && !configuredAppId.equals(target.appId())) {
      throw new IllegalStateException("支付宝 chatId appId 与配置不一致（chat=" + chatId + "）");
    }
    client.sendText(target.fromUserId(), text);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    AlipayRsa2 active = rsa;
    AlipayEventNormalizer activeNormalizer = normalizer;
    if (active == null || activeNormalizer == null) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "channel not started");
    }
    Map<String, String> params = request.query();
    if (params == null || params.isEmpty()) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "missing form params");
    }
    Charset charset = resolveCharset(params.get("charset"));
    if (!active.verify(params, charset)) {
      log.warn(
          "支付宝网关验签失败（keys={} charset={} service={}）",
          params.keySet(),
          charset.name(),
          sanitize(params.get("service")));
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "verify failed");
    }
    log.info(
        "支付宝网关验签通过（service={} event={}）",
        sanitize(params.get("service")),
        sanitize(AlipayCallbackXml.cdataOrText(params.get("biz_content"), "EventType")));
    String bizContent = params.get("biz_content");
    if (bizContent == null || bizContent.isBlank()) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "missing biz_content");
    }
    String service = params.get("service");
    String msgType = asciiLower(AlipayCallbackXml.cdataOrText(bizContent, "MsgType"));
    String eventType = asciiLower(AlipayCallbackXml.cdataOrText(bizContent, "EventType"));
    if (SERVICE_CHECK.equals(service)
        || (MSG_EVENT.equals(msgType) && EVENT_VERIFYGW.equals(eventType))) {
      return verifygwResponse(active, charset, true);
    }
    try {
      Optional<InboundMessage> message = activeNormalizer.normalize(bizContent);
      message.ifPresent(
          m -> {
            sessions.rememberInbound(m.chatId(), clock.millis());
            dispatchOne(m);
          });
      return WebhookResponse.text(HTTP_OK, ACK_SUCCESS);
    } catch (Exception e) {
      log.warn("支付宝回调处理失败: {}", sanitize(e.getMessage()));
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "callback failed");
    }
  }

  /** 普通公钥方式 verifygw 回执。 */
  static WebhookResponse verifygwResponse(AlipayRsa2 rsa, Charset charset, boolean ok) {
    String responseXml =
        ok
            ? "<success>true</success><biz_content>" + rsa.appPublicKeyPlain() + "</biz_content>"
            : "<success>false</success><error_code>VERIFY_FAILED</error_code><biz_content>"
                + rsa.appPublicKeyPlain()
                + "</biz_content>";
    String sign = rsa.signRaw(responseXml, charset);
    String body =
        "<?xml version=\"1.0\" encoding=\""
            + charset.name()
            + "\"?><alipay><response>"
            + responseXml
            + "</response><sign>"
            + sign
            + "</sign><sign_type>"
            + AlipayRsa2.SIGN_TYPE
            + "</sign_type></alipay>";
    return new WebhookResponse(HTTP_OK, "text/xml;charset=" + charset.name(), body);
  }

  private void dispatchOne(InboundMessage m) {
    try {
      if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
        log.info("渠道 {} 重复消息已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
        return;
      }
      inboundMessageService.onClaimedMessage(m, this);
    } catch (RuntimeException e) {
      log.warn("支付宝消息分发失败（messageId={}）: {}", sanitize(m.messageId()), sanitize(e.getMessage()));
    }
  }

  private void requireExtra(String key) {
    String value = config.extra(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra." + key);
    }
  }

  private static Charset resolveCharset(String charset) {
    if (charset == null || charset.isBlank()) {
      return GATEWAY_CHARSET;
    }
    try {
      return Charset.forName(charset.trim());
    } catch (RuntimeException e) {
      return GATEWAY_CHARSET;
    }
  }

  private static String asciiLower(String value) {
    if (value == null) {
      return "";
    }
    return value.toLowerCase(Locale.ROOT);
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
