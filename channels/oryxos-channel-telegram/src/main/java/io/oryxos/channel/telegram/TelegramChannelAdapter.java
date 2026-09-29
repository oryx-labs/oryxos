package io.oryxos.channel.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Telegram 入站：Bot API {@code getUpdates} 长轮询（免公网回调）。
 *
 * <p>凭证：{@code app_id}=Bot Token，{@code app_secret}=Bot 用户名（群 {@code @Bot} 匹配，可带或不带 @）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "sender/normalizer 在 start() 内初始化；sendReply 有显式空判。")
public class TelegramChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(TelegramChannelAdapter.class);

  public static final String TYPE = "telegram";
  static final String DEFAULT_API_BASE = "https://api.telegram.org";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(35);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIELD_OK = "ok";
  private static final String FIELD_RESULT = "result";
  private static final String FIELD_UPDATE_ID = "update_id";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final String apiBase;

  private volatile TelegramEventNormalizer normalizer;
  private volatile TelegramMessageSender sender;
  private volatile HttpClient http;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;
  private volatile boolean running;
  private volatile Thread pollThread;
  private volatile long offset;

  public TelegramChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, DEFAULT_API_BASE);
  }

  TelegramChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      String apiBase) {
    this.config = config;
    this.profileRegistry = profileRegistry;
    this.inboundMessageService = inboundMessageService;
    this.guard = guard;
    this.apiBase = apiBase;
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
    String probe = trimSlash(apiBase) + "/bot" + config.appId() + "/getMe";
    guard.check(probe);
    running = true;
    offset = 0;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    normalizer = new TelegramEventNormalizer(config.name(), config.appSecret());
    sender = new TelegramMessageSender(guard, apiBase, config.appId());
    pollThread = Thread.ofVirtual().name("oryxos-telegram-" + config.name()).start(this::pollLoop);
    state = ChannelStatus.State.CONNECTED;
    lastError = null;
    LOG.info("Telegram 渠道 {} 长轮询已启动（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
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
    TelegramMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text, replyToMessageId);
  }

  private void pollLoop() {
    while (running) {
      try {
        JsonNode result = getUpdates();
        if (result != null && result.isArray()) {
          for (JsonNode update : result) {
            long updateId = update.path(FIELD_UPDATE_ID).asLong(0);
            if (updateId >= offset) {
              offset = updateId + 1;
            }
            Optional<InboundMessage> msg = normalizer.normalize(update);
            msg.ifPresent(this::dispatch);
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
        LOG.warn(
            "Telegram 渠道 {} getUpdates 失败: {}", sanitize(config.name()), sanitize(e.getMessage()));
        lastError = sanitize(e.getMessage());
        sleepQuietly(2_000L);
      }
    }
  }

  private void dispatch(InboundMessage message) {
    InboundMessage enriched = resolveMedia(message);
    inboundMessageService.onMessage(enriched, this);
  }

  private InboundMessage resolveMedia(InboundMessage message) {
    TelegramMessageSender current = sender;
    if (current == null || message.attachments().isEmpty()) {
      return message;
    }
    List<InboundAttachment> resolved = new ArrayList<>();
    for (InboundAttachment attachment : message.attachments()) {
      if (attachment.url() != null && !attachment.url().isBlank()) {
        resolved.add(attachment);
        continue;
      }
      if (attachment.reference() == null || attachment.reference().isBlank()) {
        resolved.add(attachment);
        continue;
      }
      try {
        String url = current.resolveFileUrl(attachment.reference());
        resolved.add(
            new InboundAttachment(
                attachment.type(), url, attachment.reference(), attachment.fileName()));
      } catch (RuntimeException e) {
        LOG.warn("Telegram 附件 getFile 失败，保留 file_id: {}", sanitize(e.getMessage()));
        resolved.add(attachment);
      }
    }
    return new InboundMessage(
        message.channelType(),
        message.channelName(),
        message.messageId(),
        message.chatKind(),
        message.userId(),
        message.chatId(),
        message.content(),
        message.textual(),
        message.mentionedBot(),
        resolved);
  }

  private JsonNode getUpdates() throws Exception {
    String url =
        trimSlash(apiBase) + "/bot" + config.appId() + "/getUpdates?timeout=25&offset=" + offset;
    guard.check(url);
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create(url)).timeout(POLL_TIMEOUT).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (Thread.interrupted()) {
      throw new InterruptedException("poll interrupted");
    }
    JsonNode root = MAPPER.readTree(response.body() == null ? "{}" : response.body());
    if (root == null || !root.path(FIELD_OK).asBoolean(false)) {
      throw new IllegalStateException("getUpdates 失败: " + sanitize(response.body()));
    }
    return root.get(FIELD_RESULT);
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String trimSlash(String base) {
    String s = base.strip();
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
