package io.oryxos.channel.matrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMediaRoots;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Matrix 入站：Client-Server {@code /sync} 长轮询。{@code app_id}=bot MXID，{@code app_secret}=access
 * token，{@code extra.homeserver}。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification = "http/normalizer/sender 在 start() 内初始化；sync 循环仅在 start 后运行。")
public class MatrixChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(MatrixChannelAdapter.class);

  public static final String TYPE = "matrix";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final Duration SYNC_TIMEOUT = Duration.ofSeconds(40);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EXTRA_HOMESERVER = "homeserver";
  private static final String FIELD_NEXT_BATCH = "next_batch";
  private static final String FIELD_ROOMS = "rooms";
  private static final String FIELD_JOIN = "join";
  private static final String FIELD_INVITE = "invite";
  private static final String FIELD_TIMELINE = "timeline";
  private static final String FIELD_EVENTS = "events";
  private static final String MEDIA_DIR_PREFIX = "oryxos-mx-media-";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile MatrixEventNormalizer normalizer;
  private volatile MatrixMessageSender sender;
  private volatile MatrixInboundMediaResolver mediaResolver;
  private volatile MatrixDirectRooms directRooms;
  private volatile HttpClient http;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile boolean running;
  private volatile Thread pollThread;
  private volatile String since;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public MatrixChannelAdapter(
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
    if (config.extra(EXTRA_HOMESERVER) == null || config.extra(EXTRA_HOMESERVER).isBlank()) {
      throw new IllegalArgumentException("渠道 " + config.name() + " 缺少 extra.homeserver");
    }
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(config.extra(EXTRA_HOMESERVER));
    running = true;
    since = null;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    normalizer = new MatrixEventNormalizer(config.name(), config.appId());
    sender = new MatrixMessageSender(guard, config.extra(EXTRA_HOMESERVER), config.appSecret());
    mediaResolver =
        new MatrixInboundMediaResolver(
            guard,
            config.extra(EXTRA_HOMESERVER),
            config.appSecret(),
            InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX),
            config.name());
    directRooms = new MatrixDirectRooms();
    seedDirectRooms();
    pollThread = Thread.ofVirtual().name("oryxos-matrix-" + config.name()).start(this::syncLoop);
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    running = false;
    Thread t = pollThread;
    if (t != null) {
      t.interrupt();
      pollThread = null;
    }
    mediaResolver = null;
    state = ChannelStatus.State.DISCONNECTED;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    MatrixMessageSender current = sender;
    if (current == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    current.send(chatId, text);
  }

  private void syncLoop() {
    while (running) {
      try {
        JsonNode root = syncOnce();
        if (root != null) {
          since = root.path(FIELD_NEXT_BATCH).asText(since);
          MatrixDirectRooms rooms = directRooms;
          if (rooms != null) {
            rooms.mergeFromSync(root);
          }
          acceptInvites(root.path(FIELD_ROOMS).path(FIELD_INVITE));
          dispatchJoin(root.path(FIELD_ROOMS).path(FIELD_JOIN));
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception e) {
        if (!running) {
          return;
        }
        LOG.warn("Matrix 渠道 {} sync 失败: {}", sanitize(config.name()), sanitize(e.getMessage()));
        sleepQuietly(2_000L);
      }
    }
  }

  private void acceptInvites(JsonNode invite) {
    if (invite == null || !invite.isObject() || http == null) {
      return;
    }
    Iterator<String> rooms = invite.fieldNames();
    while (rooms.hasNext()) {
      String roomId = rooms.next();
      if (inviteLooksDirect(invite.get(roomId))) {
        MatrixDirectRooms directs = directRooms;
        if (directs != null) {
          directs.remember(roomId);
        }
      }
      try {
        String homeserver = MatrixMessageSender.trimSlash(config.extra(EXTRA_HOMESERVER));
        String url =
            homeserver
                + "/_matrix/client/v3/join/"
                + URLEncoder.encode(roomId, StandardCharsets.UTF_8);
        guard.check(url);
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + config.appSecret())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= HTTP_STATUS_OK_MIN
            && response.statusCode() < HTTP_STATUS_OK_MAX_EXCLUSIVE) {
          LOG.info("Matrix 渠道 {} 已加入邀请房间", sanitize(config.name()));
        } else {
          LOG.warn("Matrix 渠道 {} 加入邀请失败 HTTP {}", sanitize(config.name()), response.statusCode());
        }
      } catch (IOException | InterruptedException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        LOG.warn("Matrix 渠道 {} 加入邀请异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
      }
    }
  }

  static boolean inviteLooksDirect(JsonNode inviteRoom) {
    if (inviteRoom == null) {
      return false;
    }
    JsonNode events = inviteRoom.path("invite_state").path("events");
    if (!events.isArray()) {
      return false;
    }
    for (JsonNode event : events) {
      if ("m.room.member".equals(event.path("type").asText(""))
          && event.path("content").path("is_direct").asBoolean(false)) {
        return true;
      }
    }
    return false;
  }

  private void dispatchJoin(JsonNode join) {
    if (join == null || !join.isObject()) {
      return;
    }
    Iterator<Map.Entry<String, JsonNode>> rooms = join.fields();
    while (rooms.hasNext()) {
      Map.Entry<String, JsonNode> room = rooms.next();
      MatrixDirectRooms directs = directRooms;
      boolean direct = directs != null && directs.isDirect(room.getKey());
      JsonNode events = room.getValue().path(FIELD_TIMELINE).path(FIELD_EVENTS);
      if (!events.isArray()) {
        continue;
      }
      for (JsonNode event : events) {
        Optional<InboundMessage> msg = normalizer.normalize(room.getKey(), event, direct);
        msg.ifPresent(this::dispatch);
      }
    }
  }

  private void dispatch(InboundMessage incoming) {
    if (!inboundMessageService.tryClaim(incoming.channelName(), incoming.messageId())) {
      LOG.info(
          "渠道 {} 重复事件已忽略: {}", sanitize(incoming.channelName()), sanitize(incoming.messageId()));
      return;
    }
    MatrixInboundMediaResolver resolver = mediaResolver;
    InboundMessage discovered = incoming;
    CountDownLatch slow = null;
    if (resolver != null && MatrixInboundMediaResolver.needsDownload(discovered)) {
      slow = inboundMessageService.beginSlowWork(this, discovered.chatId(), discovered.messageId());
      discovered = resolver.download(discovered);
    }
    if (!discovered.processable()) {
      if (slow != null) {
        slow.countDown();
      }
      return;
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

  private void seedDirectRooms() {
    MatrixDirectRooms rooms = directRooms;
    if (rooms == null || http == null) {
      return;
    }
    try {
      String homeserver = MatrixMessageSender.trimSlash(config.extra(EXTRA_HOMESERVER));
      String user = URLEncoder.encode(config.appId(), StandardCharsets.UTF_8);
      String url = homeserver + "/_matrix/client/v3/user/" + user + "/account_data/m.direct";
      guard.check(url);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + config.appSecret())
              .GET()
              .build();
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= HTTP_STATUS_OK_MIN
          && response.statusCode() < HTTP_STATUS_OK_MAX_EXCLUSIVE
          && response.body() != null
          && !response.body().isBlank()) {
        rooms.replaceFromContent(MAPPER.readTree(response.body()));
      }
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOG.debug(
          "Matrix 渠道 {} 预载 m.direct 跳过: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private JsonNode syncOnce() throws Exception {
    String homeserver = MatrixMessageSender.trimSlash(config.extra(EXTRA_HOMESERVER));
    String url = homeserver + "/_matrix/client/v3/sync?timeout=30000";
    if (since != null && !since.isBlank()) {
      url += "&since=" + URLEncoder.encode(since, StandardCharsets.UTF_8);
    }
    guard.check(url);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(SYNC_TIMEOUT)
            .header("Authorization", "Bearer " + config.appSecret())
            .GET()
            .build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (Thread.interrupted()) {
      throw new InterruptedException("sync interrupted");
    }
    if (response.statusCode() < HTTP_STATUS_OK_MIN
        || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException("sync HTTP " + response.statusCode());
    }
    return MAPPER.readTree(response.body() == null ? "{}" : response.body());
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
