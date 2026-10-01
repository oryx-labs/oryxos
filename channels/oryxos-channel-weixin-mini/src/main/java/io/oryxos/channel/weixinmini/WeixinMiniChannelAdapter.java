package io.oryxos.channel.weixinmini;

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
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信小程序客服：消息推送 URL 验签/解密 → 文本归一化 → 快速 success → Agent → {@code message/custom/send}。
 *
 * <p>{@code app_id}=小程序 AppId，{@code app_secret}=AppSecret；{@code extra.token} / {@code
 * encoding_aes_key}。
 */
public class WeixinMiniChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "weixin_mini";

  private static final Logger log = LoggerFactory.getLogger(WeixinMiniChannelAdapter.class);
  private static final String EXTRA_TOKEN = "token";
  private static final String EXTRA_AES_KEY = "encoding_aes_key";
  private static final String METHOD_GET = "get";
  private static final String QUERY_SIGNATURE = "signature";
  private static final String QUERY_MSG_SIGNATURE = "msg_signature";
  private static final String QUERY_TIMESTAMP = "timestamp";
  private static final String QUERY_NONCE = "nonce";
  private static final String QUERY_ECHOSTR = "echostr";
  private static final String TAG_ENCRYPT = "Encrypt";
  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNAUTHORIZED = 401;
  private static final String ACK_SUCCESS = "success";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Clock clock;
  private final WeixinMiniReplySessionStore sessions = new WeixinMiniReplySessionStore();

  private volatile WeixinMiniMsgCrypt crypt;
  private volatile WeixinMiniClient api;
  private volatile WeixinMiniEventNormalizer normalizer;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  public WeixinMiniChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, Clock.systemUTC());
  }

  WeixinMiniChannelAdapter(
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

  /** 测试注入 API 客户端与加解密。 */
  WeixinMiniChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      Clock clock,
      WeixinMiniClient api,
      WeixinMiniMsgCrypt crypt) {
    this(config, profileRegistry, inboundMessageService, guard, clock);
    this.api = api;
    this.crypt = crypt;
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
    requireExtra(EXTRA_TOKEN);
    requireExtra(EXTRA_AES_KEY);
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(WeixinMiniApiClient.API_BASE);
    if (crypt == null) {
      crypt =
          new WeixinMiniMsgCrypt(
              config.extra(EXTRA_TOKEN), config.extra(EXTRA_AES_KEY), config.appId());
    }
    if (api == null) {
      WeixinMiniAccessTokenClient tokens =
          new WeixinMiniAccessTokenClient(guard, config.appId(), config.appSecret());
      api = new WeixinMiniApiClient(guard, tokens::getToken);
    }
    normalizer = new WeixinMiniEventNormalizer(config.name(), config.appId());
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
    WeixinMiniClient client = api;
    if (client == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    WeixinMiniReplySessionStore.Session session = sessions.requireForReply(chatId, clock.millis());
    WeixinMiniChatTargets.Parsed target = WeixinMiniChatTargets.parse(chatId);
    String configuredAppId = config.appId();
    if (configuredAppId != null
        && !configuredAppId.isBlank()
        && !configuredAppId.equals(target.appId())) {
      throw new IllegalStateException("小程序 chatId appId 与配置不一致（chat=" + chatId + "）");
    }
    client.sendText(target.openId(), text);
    sessions.markReplied(chatId, session);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    if (METHOD_GET.equals(asciiLower(request.method()))) {
      return handleUrlVerify(request);
    }
    return handleCallback(request);
  }

  private WebhookResponse handleUrlVerify(WebhookRequest request) {
    WeixinMiniMsgCrypt active = crypt;
    if (active == null) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "channel not started");
    }
    try {
      String echostr =
          active.verifyUrlPlain(
              request.query(QUERY_SIGNATURE),
              request.query(QUERY_TIMESTAMP),
              request.query(QUERY_NONCE),
              request.query(QUERY_ECHOSTR));
      return WebhookResponse.text(HTTP_OK, echostr);
    } catch (Exception e) {
      log.warn("小程序 URL 校验失败: {}", sanitize(e.getMessage()));
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "verify failed");
    }
  }

  private WebhookResponse handleCallback(WebhookRequest request) {
    WeixinMiniMsgCrypt active = crypt;
    WeixinMiniEventNormalizer activeNormalizer = normalizer;
    if (active == null || activeNormalizer == null) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "channel not started");
    }
    try {
      String encrypt = WeixinMiniCallbackXml.cdataOrText(request.body(), TAG_ENCRYPT);
      if (encrypt == null || encrypt.isBlank()) {
        return WebhookResponse.text(HTTP_BAD_REQUEST, "missing Encrypt");
      }
      String xml =
          active.decryptMsg(
              request.query(QUERY_MSG_SIGNATURE),
              request.query(QUERY_TIMESTAMP),
              request.query(QUERY_NONCE),
              encrypt);
      Optional<InboundMessage> message = activeNormalizer.normalize(xml);
      message.ifPresent(
          m -> {
            sessions.rememberInbound(m.chatId(), clock.millis());
            dispatchOne(m);
          });
      return WebhookResponse.text(HTTP_OK, ACK_SUCCESS);
    } catch (Exception e) {
      log.warn("小程序回调处理失败: {}", sanitize(e.getMessage()));
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "callback failed");
    }
  }

  private void dispatchOne(InboundMessage m) {
    try {
      if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
        log.info("渠道 {} 重复消息已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
        return;
      }
      inboundMessageService.onClaimedMessage(m, this);
    } catch (RuntimeException e) {
      log.warn("小程序消息分发失败（messageId={}）: {}", sanitize(m.messageId()), sanitize(e.getMessage()));
    }
  }

  private void requireExtra(String key) {
    String value = config.extra(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra." + key);
    }
  }

  private static String asciiLower(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  private static String sanitize(String value) {
    if (value == null) {
      return "";
    }
    String trimmed = value.length() > 200 ? value.substring(0, 200) : value;
    return trimmed.replace('\r', '_').replace('\n', '_');
  }
}
