package io.oryxos.channel.feishu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.core.httpclient.OkHttpTransport;
import com.lark.oapi.core.response.RawResponse;
import com.lark.oapi.core.token.AccessTokenType;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.okhttp.OkHttpClient;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.ChatKind;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书入站渠道适配器（017 T013）：一实例 = 一个飞书自建应用的一条长连接。
 *
 * <p>长连接免公网回调、免验签（通道自带加密与鉴权，R2）——{@code EventDispatcher.newBuilder("", "")} 两参数按官方要求 填空串。SDK
 * 自动重连（无限重试 + 心跳）白拿，重连回调维护状态供 status 端点呈现。
 *
 * <p>契约规则 A4：start 前置校验凭证已解析、绑定 Agent 存在，失败抛点名异常、渠道不上线（FR-013/SC-008）。 事件 handler
 * 不抛异常（抛出会触发平台重推循环）；归一化返回 empty 的事件（非 @ 群消息等）直接丢弃。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR",
    justification =
        "apiClient/normalizer/sender/imageResolver 在 start() 内初始化（长连接生命周期晚于构造）；事件回调只会在 start 成功后由 SDK 触发，sendReply 有显式空判，不存在未初始化解引用路径。")
public class FeishuChannelAdapter implements InboundChannelAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuChannelAdapter.class);

  public static final String TYPE = "feishu";
  private static final String API_BASE_URL = "https://open.feishu.cn";
  private static final String BOT_INFO_PATH = "/open-apis/bot/v3/info";
  private static final String BOT_OPEN_ID_FIELD = "open_id";
  private static final String MEDIA_DIR_PREFIX = "oryxos-feishu-media-";
  private static final String MEDIA_FALLBACK_DIR = "oryxos-feishu-media";
  private static final int HTTP_OK = 200;
  private static final long READY_TIMEOUT_MS = 15_000;
  private static final long READY_PROBE_TIMEOUT_MS = 50;

  /**
   * 入站图片下载超时。SDK {@code requestTimeout} 只设置 OkHttp {@code callTimeout}，仍保留默认 {@code
   * readTimeout=10s}，慢网/大图会在读 body 时 {@code client time out}。须自定义 transport 同时拉长 read/call。
   */
  private static final long API_CONNECT_TIMEOUT_SEC = 30;

  private static final long API_READ_TIMEOUT_SEC = 180;
  private static final long API_CALL_TIMEOUT_SEC = 180;

  private final ChannelConfig config; // resolved 口径（凭证为真实值，仅存活内存）
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;

  private volatile com.lark.oapi.Client apiClient;
  private volatile com.lark.oapi.ws.Client wsClient;
  private volatile FeishuMessageSender sender;
  private volatile FeishuEventNormalizer normalizer;
  private volatile FeishuInboundImageResolver imageResolver;
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;
  private volatile String lastError;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "协作者均为 Runtime 装配的单例，共享引用正是意图")
  public FeishuChannelAdapter(
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
    // A4 前置校验：凭证已解析（点名报错）+ 绑定 Agent 存在，失败渠道不上线
    config.validateCredentialsResolved();
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(API_BASE_URL); // 出站白名单在建连前即校验，缺域名尽早点名
    try {
      apiClient =
          com.lark.oapi.Client.newBuilder(config.appId(), config.appSecret())
              .httpTransport(
                  new OkHttpTransport(
                      new OkHttpClient.Builder()
                          .connectTimeout(API_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                          .readTimeout(API_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                          .writeTimeout(API_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                          .callTimeout(API_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
                          .build()))
              .build();
      sender =
          new FeishuMessageSender(
              apiClient, guard, API_BASE_URL, FeishuMessageSender.DEFAULT_CHUNK_SIZE);
      normalizer = new FeishuEventNormalizer(config.name(), fetchBotOpenId());
      imageResolver = new FeishuInboundImageResolver(apiClient, createMediaRoot(), config.name());
      EventDispatcher dispatcher =
          EventDispatcher.newBuilder("", "") // 长连接免验签：verificationToken/encryptKey 必须空串
              .onP2MessageReceiveV1(
                  new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                      handleEvent(event);
                    }
                  })
              .build();
      com.lark.oapi.ws.Client ws =
          new com.lark.oapi.ws.Client.Builder(config.appId(), config.appSecret())
              .eventHandler(dispatcher)
              .autoReconnect(true)
              .onReconnecting(() -> state = ChannelStatus.State.DISCONNECTED)
              .onReconnected(() -> state = ChannelStatus.State.CONNECTED)
              .build();
      try {
        ws.start();
        ws.awaitReady(READY_TIMEOUT_MS);
      } catch (Exception e) {
        // start/awaitReady 失败的 ws.Client 不会交给 wsClient 字段，stop() 永远碰不到它；
        // autoReconnect 已开启，不就地 close 会留下永久后台重连的幽灵连接 + 线程泄漏
        closeQuietly(ws);
        throw e;
      }
      wsClient = ws;
      state = ChannelStatus.State.CONNECTED;
      lastError = null;
      LOG.info("飞书渠道 {} 长连接已建立（Agent: {}）", sanitize(config.name()), sanitize(config.agent()));
    } catch (Exception e) {
      state = ChannelStatus.State.ERROR;
      lastError = "长连接建立失败: " + sanitize(e.getMessage());
      throw new IllegalStateException("渠道 " + config.name() + " " + lastError, e);
    }
  }

  @Override
  public synchronized void stop() {
    com.lark.oapi.ws.Client ws = wsClient;
    if (ws != null) {
      closeQuietly(ws);
      wsClient = null;
    }
    state = ChannelStatus.State.DISCONNECTED;
  }

  /** 关闭 WS 客户端，异常仅告警不上抛——断开路径的异常不影响调用方语义。 */
  private void closeQuietly(com.lark.oapi.ws.Client ws) {
    try {
      ws.close();
    } catch (RuntimeException e) {
      LOG.warn("飞书渠道 {} 断开时异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  /**
   * 实时状态：以 {@code awaitReady} 主动探测连接就绪（SDK 内部 readyFuture 重连中被重置、就绪后完成）， 不依赖 onReconnected
   * 回调——真机验证发现快速重连路径（如对端 Connection reset 后立即重连）不触发该回调， 仅靠回调会把活连接误报为 DISCONNECTED。探测超时取
   * 50ms，状态查询不阻塞。
   */
  @Override
  public ChannelStatus status() {
    if (state == ChannelStatus.State.ERROR) {
      return ChannelStatus.error(config.name(), TYPE, config.agent(), lastError);
    }
    com.lark.oapi.ws.Client ws = wsClient;
    if (ws == null) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
    try {
      ws.awaitReady(READY_PROBE_TIMEOUT_MS);
      return ChannelStatus.ok(config.name(), TYPE, config.agent(), ChannelStatus.State.CONNECTED);
    } catch (Exception e) {
      return ChannelStatus.ok(
          config.name(), TYPE, config.agent(), ChannelStatus.State.DISCONNECTED);
    }
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    FeishuMessageSender active = sender;
    if (active == null) {
      throw new IllegalStateException("渠道 " + config.name() + " 未启动，无法发送回复");
    }
    active.send(chatId, text, replyToMessageId);
  }

  /**
   * 事件入口：归一化 → 去重占用 →（需下载时先开「处理中」）→ 图片资源落地 → 编排。去重必须在下载前：飞书平台重推同一 message_id
   * 时，若先下载再去重会白耗带宽。任何异常只留日志——抛出会触发平台重推循环。
   */
  private void handleEvent(P2MessageReceiveV1 event) {
    try {
      Optional<InboundMessage> msg = normalizer.normalize(event);
      msg.ifPresent(
          m -> {
            if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
              LOG.info("渠道 {} 重复事件已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
              return;
            }
            String replyTo = m.chatKind() == ChatKind.GROUP ? m.messageId() : null;
            java.util.concurrent.CountDownLatch slowWork = null;
            if (needsImageDownload(m)) {
              // 下载常超过默认「处理中」阈值；计时从下载前开始，避免用户长时间无反馈
              slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), replyTo);
            }
            try {
              FeishuInboundImageResolver resolver = imageResolver;
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
          });
    } catch (RuntimeException e) {
      LOG.error("飞书渠道 {} 事件处理异常: {}", sanitize(config.name()), sanitize(e.getMessage()));
    }
  }

  private static boolean needsImageDownload(InboundMessage message) {
    for (InboundAttachment attachment : message.attachments()) {
      if (isUnresolvedImageAttachment(attachment)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isUnresolvedImageAttachment(InboundAttachment attachment) {
    if (!InboundAttachment.TYPE_IMAGE.equals(attachment.type())) {
      return false;
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return false;
    }
    return attachment.reference() != null && !attachment.reference().isBlank();
  }

  /** 入站图片落盘目录：进程临时目录下按渠道隔离；失败不阻断 start（解析器会降级保留 image_key）。 */
  private Path createMediaRoot() {
    String channelSeg = FeishuInboundImageResolver.safeSegment(config.name());
    try {
      // 前缀必须是常量字面量：SpotBugs 视 createTempDirectory(userInput+…) 为 PATH_TRAVERSAL_IN
      Path root = Files.createTempDirectory(MEDIA_DIR_PREFIX);
      Path channelDir = root.resolve(channelSeg);
      Files.createDirectories(channelDir);
      return channelDir;
    } catch (Exception e) {
      LOG.warn(
          "飞书渠道 {} 创建图片缓存目录失败（{}），入站图片将保留 image_key",
          sanitize(config.name()),
          sanitize(e.getMessage()));
      return Path.of(System.getProperty("java.io.tmpdir"), MEDIA_FALLBACK_DIR, channelSeg);
    }
  }

  /**
   * 获取机器人自身 open_id（供群聊 @ 判定）：GET /open-apis/bot/v3/info（tenant token，SDK 无高层封装走 raw）。 失败降级返回
   * null——归一化按 mentioned_type 判定并 WARN（默认权限下飞书只推 @ 本机器人的群消息，降级安全）。
   */
  private String fetchBotOpenId() {
    try {
      RawResponse resp = apiClient.get(BOT_INFO_PATH, null, AccessTokenType.Tenant);
      if (resp == null || resp.getStatusCode() != HTTP_OK || resp.getBody() == null) {
        LOG.warn(
            "飞书渠道 {} 获取机器人身份失败（HTTP {}），群聊 @ 判定降级",
            sanitize(config.name()),
            resp == null ? -1 : resp.getStatusCode());
        return null;
      }
      JsonElement root = JsonParser.parseString(new String(resp.getBody(), StandardCharsets.UTF_8));
      JsonObject obj = root.getAsJsonObject();
      JsonObject bot =
          obj.has("bot") && obj.get("bot").isJsonObject()
              ? obj.getAsJsonObject("bot")
              : obj.has("data") && obj.get("data").isJsonObject()
                  ? obj.getAsJsonObject("data").getAsJsonObject("bot")
                  : null;
      if (bot == null || bot.get(BOT_OPEN_ID_FIELD) == null) {
        LOG.warn("飞书渠道 {} 机器人身份响应缺 open_id，群聊 @ 判定降级", sanitize(config.name()));
        return null;
      }
      return bot.get(BOT_OPEN_ID_FIELD).getAsString();
    } catch (Exception e) {
      LOG.warn(
          "飞书渠道 {} 获取机器人身份异常（{}），群聊 @ 判定降级", sanitize(config.name()), sanitize(e.getMessage()));
      return null;
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
