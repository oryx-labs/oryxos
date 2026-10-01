package io.oryxos.channel.gchat;

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
import java.util.Optional;

/** Google Chat HTTP 端点入站。{@code app_id}=Bot 资源名（可选审计），{@code app_secret}=Chat API access token。 */
public class GoogleChatChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "gchat";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int HTTP_BAD_REQUEST = 400;

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile GoogleChatEventNormalizer normalizer;
  private volatile GoogleChatMessageSender sender;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public GoogleChatChannelAdapter(
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
    guard.check(GoogleChatMessageSender.API_BASE);
    normalizer = new GoogleChatEventNormalizer(config.name());
    sender = new GoogleChatMessageSender(guard, config.appSecret());
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
    GoogleChatMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text, replyToMessageId);
  }

  @Override
  public WebhookResponse onWebhook(WebhookRequest request) {
    try {
      JsonNode root = MAPPER.readTree(request.body().isBlank() ? "{}" : request.body());
      Optional<InboundMessage> msg =
          normalizer == null ? Optional.empty() : normalizer.normalize(root);
      msg.ifPresent(m -> inboundMessageService.onMessage(m, this));
      return WebhookResponse.ok();
    } catch (JacksonException e) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "bad payload");
    }
  }
}
