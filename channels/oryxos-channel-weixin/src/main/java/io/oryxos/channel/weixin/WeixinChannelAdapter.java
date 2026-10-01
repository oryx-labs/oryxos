package io.oryxos.channel.weixin;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMediaRoots;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 个人微信 iLink Bot：长轮询入站 + {@code sendmessage} 回复。
 *
 * <p>定位：个人 ↔ OryxOS Harness / 自用 Agent 通道（对齐 OpenClaw openclaw-weixin）。 {@code
 * app_id}=ilink_bot_id，{@code app_secret}=bot_token。支持私聊文本与图/语音/文件/视频入站。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR", "EI_EXPOSE_REP2"},
    justification =
        "client/normalizer/sender/mediaResolver 在 start() 内初始化；sendReply 有显式空判。"
            + "构造注入的 registry/service/guard 为 Spring/运行时单例，不对外再暴露可变副本。")
public class WeixinChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(WeixinChannelAdapter.class);

  public static final String TYPE = "weixin";

  private static final String EXTRA_BASE_URL = "base_url";
  private static final String EXTRA_CDN_BASE_URL = "cdn_base_url";
  private static final String MEDIA_DIR_PREFIX = "weixin";
  private static final Duration DEFAULT_LONG_POLL = Duration.ofSeconds(35);
  private static final int SESSION_EXPIRED = -14;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final WeixinContextTokenStore tokens = new WeixinContextTokenStore();
  private final AtomicReference<String> syncBuf = new AtomicReference<>("");

  private volatile WeixinIlinkClient client;
  private volatile WeixinEventNormalizer normalizer;
  private volatile WeixinMessageSender sender;
  private volatile WeixinInboundMediaResolver mediaResolver;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile Thread pollThread;

  public WeixinChannelAdapter(
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
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    String base =
        Optional.ofNullable(config.extra(EXTRA_BASE_URL)).filter(s -> !s.isBlank()).orElse(null);
    String baseForGuard = base == null ? WeixinIlinkClient.DEFAULT_BASE : base;
    guard.check(baseForGuard);
    String cdn =
        Optional.ofNullable(config.extra(EXTRA_CDN_BASE_URL))
            .filter(s -> !s.isBlank())
            .orElse(WeixinEventNormalizer.DEFAULT_CDN_BASE);
    guard.check(cdn);
    client = new WeixinIlinkClient(guard, baseForGuard, config::appSecret);
    normalizer = new WeixinEventNormalizer(config.name(), config.appId(), cdn);
    sender = new WeixinMessageSender(client);
    mediaResolver =
        new WeixinInboundMediaResolver(
            guard, InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX), config.name());
    running = true;
    pollThread = Thread.ofVirtual().name("oryxos-weixin-" + config.name()).start(this::pollLoop);
    state = ChannelStatus.State.CONNECTED;
    lastError = null;
    LOG.info(
        "微信 iLink 渠道 {} 长轮询已启动（Agent: {}，个人 Harness，多模入站）",
        sanitize(config.name()),
        sanitize(config.agent()));
  }

  @Override
  public synchronized void stop() {
    running = false;
    Thread t = pollThread;
    if (t != null) {
      t.interrupt();
      pollThread = null;
    }
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(name(), TYPE, boundAgent(), lastError);
    }
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    WeixinMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    String userId = WeixinChatTargets.parseUserId(chatId);
    String contextToken = tokens.require(userId);
    current.sendReply(userId, text, contextToken);
  }

  private void pollLoop() {
    Duration timeout = DEFAULT_LONG_POLL;
    while (running) {
      try {
        WeixinIlinkClient active = client;
        WeixinEventNormalizer norm = normalizer;
        if (active == null || norm == null) {
          return;
        }
        JsonNode root = active.getUpdates(syncBuf.get(), timeout.plusSeconds(5));
        int suggested = root.path("longpolling_timeout_ms").asInt(0);
        if (suggested > 0) {
          timeout = Duration.ofMillis(suggested);
        }
        int ret = root.path("ret").asInt(0);
        int errcode = root.path("errcode").asInt(0);
        if (ret != 0 || errcode != 0) {
          if (ret == SESSION_EXPIRED || errcode == SESSION_EXPIRED) {
            lastError = "session expired";
            LOG.error("微信渠道 {} iLink 会话过期，暂停 10 分钟", sanitize(config.name()));
            sleepQuietly(600_000L);
            continue;
          }
          lastError = "ret=" + ret + " errcode=" + errcode;
          LOG.warn("微信渠道 {} getupdates 失败: {}", sanitize(config.name()), sanitize(lastError));
          sleepQuietly(2_000L);
          continue;
        }
        String newBuf = root.path("get_updates_buf").asText("");
        if (!newBuf.isBlank()) {
          syncBuf.set(newBuf);
        }
        JsonNode msgs = root.path("msgs");
        if (msgs.isArray()) {
          for (JsonNode msg : msgs) {
            String from = WeixinEventNormalizer.fromUserId(msg);
            String ctx = WeixinEventNormalizer.contextToken(msg);
            tokens.remember(from, ctx);
            Optional<InboundMessage> inbound = norm.normalize(msg);
            inbound.ifPresent(this::dispatch);
          }
        }
        lastError = null;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (!running) {
          return;
        }
        lastError = sanitize(e.getMessage());
        LOG.warn("微信渠道 {} 轮询异常: {}", sanitize(config.name()), sanitize(lastError));
        sleepQuietly(2_000L);
      }
    }
  }

  private void dispatch(InboundMessage m) {
    if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
      LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
      return;
    }
    CountDownLatch slowWork = null;
    if (WeixinInboundMediaResolver.hasDownloadableMedia(m)) {
      slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), null);
    }
    try {
      WeixinInboundMediaResolver resolver = mediaResolver;
      InboundMessage enriched = resolver == null ? m : resolver.resolve(m);
      if (slowWork != null) {
        inboundMessageService.onClaimedMessage(enriched, this, slowWork);
      } else {
        inboundMessageService.onClaimedMessage(enriched, this);
      }
    } catch (RuntimeException e) {
      if (slowWork != null) {
        slowWork.countDown();
      }
      throw e;
    }
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
