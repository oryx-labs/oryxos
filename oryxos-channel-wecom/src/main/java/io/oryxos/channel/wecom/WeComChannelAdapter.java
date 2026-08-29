package io.oryxos.channel.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企微智能机器人入站适配器：一实例 = 一个 Bot 的一条长连接（对称飞书 017）。
 *
 * <p>凭证映射：{@code app_id} = BotID，{@code app_secret} = 长连接 Secret。长连接免公网回调、免 EncodingAESKey。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "ws/normalizer/sender 在 start() 内初始化；sendReply 有显式空判。")
public class WeComChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(WeComChannelAdapter.class);

  public static final String TYPE = "wecom";

  /** OutboundGuard / 白名单用 https 形式主机名（与 wss 同域）。 */
  static final String OUTBOUND_URL = "https://openws.work.weixin.qq.com";

  private static final String CMD_AIBOT_MSG_CALLBACK = "aibot_msg_callback";

  private static final Duration START_TIMEOUT = Duration.ofSeconds(20);

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private final AtomicReference<WeComWsClient> wsRef = new AtomicReference<>();
  private volatile WeComMessageSender sender;
  private volatile WeComEventNormalizer normalizer;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public WeComChannelAdapter(
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
    guard.check(OUTBOUND_URL);
    try {
      normalizer = new WeComEventNormalizer(config.name());
      WeComWsClient client =
          new WeComWsClient(
              config.appId(),
              config.appSecret(),
              WeComWsClient.DEFAULT_WS_URL,
              this::handleFrame,
              () -> {
                if (state != ChannelStatus.State.ERROR) {
                  state = ChannelStatus.State.DISCONNECTED;
                }
              });
      sender =
          new WeComMessageSender(
              client::sendJson, guard, OUTBOUND_URL, WeComMessageSender.DEFAULT_CHUNK_SIZE);
      client.connectAndSubscribe(START_TIMEOUT);
      wsRef.set(client);
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("企微渠道 {} 长连接已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      state = ChannelStatus.State.ERROR;
      lastError = "长连接建立失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    WeComWsClient ws = wsRef.getAndSet(null);
    if (ws != null) {
      ws.closeQuietly();
    }
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    WeComWsClient ws = wsRef.get();
    if (ws == null || !ws.isSubscribed()) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    WeComMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text);
  }

  private void handleFrame(JsonNode root) {
    String cmd = root.path("cmd").asText("");
    if (!CMD_AIBOT_MSG_CALLBACK.equals(cmd)) {
      return;
    }
    JsonNode body = root.get("body");
    try {
      Optional<InboundMessage> msg = normalizer.normalize(body);
      msg.ifPresent(
          m -> {
            sender.rememberChatType(m.chatId(), WeComEventNormalizer.chatTypeCode(body));
            inboundMessageService.onMessage(m, this);
          });
    } catch (RuntimeException e) {
      LOG.error("企微渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
