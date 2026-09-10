package io.oryxos.channel.douyin;

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
import java.time.Clock;
import java.util.Optional;

/**
 * 抖音经营私信：共享 Webhook 入站 + {@code /im/send/msg/} 场景一回复。
 *
 * <p>{@code app_id}=client_key，{@code app_secret}=client_secret；{@code extra.open_id}、{@code
 * extra.access_token}。
 */
public class DouyinChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "douyin";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EXTRA_OPEN_ID = "open_id";
  private static final String EXTRA_ACCESS_TOKEN = "access_token";
  private static final String HEADER_SIGNATURE = "x-douyin-signature";
  private static final String FIELD_EVENT = "event";
  private static final String FIELD_CONTENT = "content";
  private static final String FIELD_CHALLENGE = "challenge";
  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNAUTHORIZED = 401;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Clock clock;
  private final DouyinReplySessionStore sessions = new DouyinReplySessionStore();

  private volatile DouyinEventNormalizer normalizer;
  private volatile DouyinMessageSender sender;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  public DouyinChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, Clock.systemUTC());
  }

  DouyinChannelAdapter(
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
    requireExtra(EXTRA_OPEN_ID);
    requireExtra(EXTRA_ACCESS_TOKEN);
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(DouyinMessageSender.API_BASE);
    String openId = config.extra(EXTRA_OPEN_ID);
    normalizer = new DouyinEventNormalizer(config.name(), openId);
    sender = new DouyinMessageSender(guard, () -> config.extra(EXTRA_ACCESS_TOKEN), openId);
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
    normalizer = null;
    sender = null;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    DouyinMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    String userOpenId = DouyinChatTargets.parseUserOpenId(chatId);
    DouyinReplySessionStore.Session session = sessions.requireForReply(userOpenId, clock.millis());
    String msgId =
        replyToMessageId != null && !replyToMessageId.isBlank()
            ? replyToMessageId
            : session.lastMsgId();
    current.sendReply(userOpenId, session.conversationId(), msgId, text);
    sessions.markReplied(userOpenId, session);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    String body = request.body() == null ? "" : request.body();
    try {
      JsonNode root = MAPPER.readTree(body.isBlank() ? "{}" : body);
      if (DouyinEventNormalizer.EVENT_VERIFY.equals(text(root, FIELD_EVENT))) {
        return handleChallenge(root);
      }
      if (!DouyinWebhookSignature.matches(
          config.appSecret(), body, request.header(HEADER_SIGNATURE))) {
        return WebhookResponse.text(HTTP_UNAUTHORIZED, "invalid signature");
      }
      DouyinEventNormalizer active = normalizer;
      if (active == null) {
        return WebhookResponse.text(HTTP_BAD_REQUEST, "channel not started");
      }
      Optional<InboundMessage> message = active.normalize(root);
      message.ifPresent(
          m -> {
            String from = DouyinEventNormalizer.fromUserId(root);
            String conversationId = DouyinEventNormalizer.conversationId(root);
            String msgId = DouyinEventNormalizer.serverMessageId(root);
            sessions.rememberInbound(from, conversationId, msgId, clock.millis());
            inboundMessageService.onMessage(m, this);
          });
      return WebhookResponse.ok();
    } catch (JacksonException e) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "bad payload");
    }
  }

  private WebhookResponse handleChallenge(JsonNode root) {
    JsonNode challenge = root.path(FIELD_CONTENT).path(FIELD_CHALLENGE);
    if (challenge.isMissingNode() || challenge.isNull()) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "missing challenge");
    }
    String json = "{\"challenge\":" + challenge.toString() + "}";
    return WebhookResponse.json(HTTP_OK, json);
  }

  private static String text(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return null;
    }
    JsonNode v = node.get(field);
    if (v == null || v.isNull() || !v.isTextual()) {
      return null;
    }
    String s = v.asText();
    return s == null || s.isBlank() ? null : s;
  }

  private void requireExtra(String key) {
    String value = config.extra(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra." + key);
    }
  }
}
