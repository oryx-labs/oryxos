package io.oryxos.channel.whatsapp;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * WhatsApp Cloud API 入站：共享 webhook + Graph 发信。会话窗外 {@link #sendReply} 硬拒绝，不静默。
 *
 * <p>{@code app_id}=access token，{@code app_secret}=App Secret（X-Hub-Signature-256），{@code
 * extra.verify_token}、{@code extra.phone_number_id}。
 */
public class WhatsAppChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "whatsapp";
  static final Duration SESSION_WINDOW = Duration.ofHours(24);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EXTRA_VERIFY_TOKEN = "verify_token";
  private static final String EXTRA_PHONE_NUMBER_ID = "phone_number_id";
  private static final String METHOD_GET = "get";
  private static final String HEADER_HUB_SIGNATURE = "x-hub-signature-256";
  private static final String QUERY_HUB_MODE = "hub.mode";
  private static final String QUERY_HUB_VERIFY_TOKEN = "hub.verify_token";
  private static final String QUERY_HUB_CHALLENGE = "hub.challenge";
  private static final String HUB_MODE_SUBSCRIBE = "subscribe";
  private static final String SHA256_PREFIX = "sha256=";
  private static final String HMAC_SHA256 = "HmacSHA256";
  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNAUTHORIZED = 401;
  private static final int HTTP_FORBIDDEN = 403;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Clock clock;
  private final ConcurrentHashMap<String, Long> lastInboundMs = new ConcurrentHashMap<>();

  private volatile WhatsAppEventNormalizer normalizer;
  private volatile WhatsAppMessageSender sender;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  public WhatsAppChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, Clock.systemUTC());
  }

  WhatsAppChannelAdapter(
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
    requireExtra(EXTRA_VERIFY_TOKEN);
    requireExtra(EXTRA_PHONE_NUMBER_ID);
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(WhatsAppMessageSender.DEFAULT_GRAPH_BASE);
    normalizer = new WhatsAppEventNormalizer(config.name());
    sender = new WhatsAppMessageSender(guard, config.appId(), config.extra(EXTRA_PHONE_NUMBER_ID));
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    Long last = lastInboundMs.get(chatId);
    if (last == null || clock.millis() - last > SESSION_WINDOW.toMillis()) {
      throw new IllegalStateException(
          "WhatsApp 24 小时会话窗已关闭，只能发送已审核模板消息，拒绝静默投递（chat=" + chatId + "）");
    }
    WhatsAppMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    if (METHOD_GET.equals(asciiLower(request.method()))) {
      return handleChallenge(request);
    }
    if (!verifySignature(request.body(), request.header(HEADER_HUB_SIGNATURE))) {
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "invalid signature");
    }
    try {
      JsonNode root = MAPPER.readTree(request.body().isBlank() ? "{}" : request.body());
      String from = WhatsAppEventNormalizer.firstFrom(root);
      long ts = WhatsAppEventNormalizer.firstTimestampMs(root);
      if (from != null) {
        lastInboundMs.put(from, ts > 0L ? ts : clock.millis());
      }
      List<InboundMessage> messages = normalizer == null ? List.of() : normalizer.normalize(root);
      for (InboundMessage message : messages) {
        inboundMessageService.onMessage(message, this);
      }
      return WebhookResponse.ok();
    } catch (JacksonException e) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "bad payload");
    }
  }

  void rememberInbound(String chatId, long epochMs) {
    lastInboundMs.put(chatId, epochMs);
  }

  private WebhookResponse handleChallenge(WebhookRequest request) {
    String mode = request.query(QUERY_HUB_MODE);
    String token = request.query(QUERY_HUB_VERIFY_TOKEN);
    String challenge = request.query(QUERY_HUB_CHALLENGE);
    if (challengeAccepted(mode, token, challenge)) {
      return WebhookResponse.text(HTTP_OK, challenge);
    }
    return WebhookResponse.text(HTTP_FORBIDDEN, "verify failed");
  }

  private boolean challengeAccepted(String mode, String token, String challenge) {
    if (!HUB_MODE_SUBSCRIBE.equals(mode) || token == null || challenge == null) {
      return false;
    }
    return token.equals(config.extra(EXTRA_VERIFY_TOKEN));
  }

  private boolean verifySignature(String body, String header) {
    if (header == null || !asciiLower(header).startsWith(SHA256_PREFIX)) {
      return false;
    }
    try {
      Mac mac = Mac.getInstance(HMAC_SHA256);
      mac.init(new SecretKeySpec(config.appSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
      String expected =
          SHA256_PREFIX
              + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
      return asciiLower(header).equals(asciiLower(expected));
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  private static String asciiLower(String value) {
    if (value == null) {
      return "";
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

  private void requireExtra(String key) {
    String value = config.extra(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra." + key);
    }
  }
}
