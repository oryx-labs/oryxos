package io.oryxos.channel.mattermost;

import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMediaRoots;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.ProfileRegistry;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mattermost 入站：Outgoing Webhook。{@code app_id}=Bot 用户名，{@code app_secret}=webhook token，{@code
 * extra.base_url}；发帖 Bearer 优先 {@code extra.access_token}（PAT），否则回退 {@code app_secret}。
 */
public class MattermostChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  private static final Logger LOG = LoggerFactory.getLogger(MattermostChannelAdapter.class);
  public static final String TYPE = "mattermost";
  private static final String EXTRA_BASE_URL = "base_url";
  private static final String MEDIA_DIR_PREFIX = "oryxos-mm-media-";

  /** 发帖 PAT；缺省则复用 {@code app_secret}（Outgoing Webhook token 通常不能调 {@code /api/v4/posts}）。 */
  private static final String EXTRA_ACCESS_TOKEN = "access_token";

  private static final int HTTP_UNAUTHORIZED = 401;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile MattermostEventNormalizer normalizer;
  private volatile MattermostMessageSender sender;
  private volatile MattermostInboundMediaResolver mediaResolver;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public MattermostChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
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
    if (config.extra(EXTRA_BASE_URL) == null || config.extra(EXTRA_BASE_URL).isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra.base_url");
    }
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(config.extra(EXTRA_BASE_URL));
    String token = postToken(config);
    String baseUrl = config.extra(EXTRA_BASE_URL);
    normalizer = new MattermostEventNormalizer(config.name(), config.appId());
    sender = new MattermostMessageSender(guard, baseUrl, token);
    mediaResolver =
        new MattermostInboundMediaResolver(
            guard,
            baseUrl,
            token,
            InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX),
            config.name());
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    mediaResolver = null;
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    MattermostMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text, replyToMessageId);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    if (normalizer == null || !normalizer.tokenMatches(request.body(), config.appSecret())) {
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "invalid token");
    }
    Optional<InboundMessage> raw = normalizer.normalize(request.body());
    if (raw.isEmpty()) {
      return WebhookResponse.ok();
    }
    dispatch(raw.get());
    return WebhookResponse.ok();
  }

  private void dispatch(InboundMessage incoming) {
    if (!inboundMessageService.tryClaim(incoming.channelName(), incoming.messageId())) {
      LOG.info(
          "渠道 {} 重复事件已忽略: {}", sanitize(incoming.channelName()), sanitize(incoming.messageId()));
      return;
    }
    MattermostInboundMediaResolver resolver = mediaResolver;
    InboundMessage discovered = resolver == null ? incoming : resolver.discover(incoming);
    if (!discovered.processable()) {
      return;
    }
    CountDownLatch slow = null;
    if (resolver != null && MattermostInboundMediaResolver.needsDownload(discovered)) {
      slow = inboundMessageService.beginSlowWork(this, discovered.chatId(), discovered.messageId());
      discovered = resolver.download(discovered);
    }
    try {
      if (slow != null) {
        inboundMessageService.onClaimedMessage(discovered, this, slow);
      } else {
        inboundMessageService.onClaimedMessage(discovered, this);
      }
    } catch (RuntimeException e) {
      if (slow != null) {
        slow.countDown();
      }
      throw e;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  static String postToken(ChannelConfig config) {
    String extra = config.extra(EXTRA_ACCESS_TOKEN);
    if (extra != null && !extra.isBlank()) {
      return extra;
    }
    return config.appSecret();
  }
}
