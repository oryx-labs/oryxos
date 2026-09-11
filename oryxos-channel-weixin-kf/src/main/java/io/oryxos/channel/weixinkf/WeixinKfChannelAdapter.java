package io.oryxos.channel.weixinkf;

import com.fasterxml.jackson.databind.JsonNode;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundChannelAdapter;
import io.oryxos.core.channel.InboundMediaRoots;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.InboundWebhookHandler;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.channel.WebhookRequest;
import io.oryxos.core.channel.WebhookResponse;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信客服（企业微信 {@code kf/*}）：共享 Webhook 唤醒 + {@code sync_msg} 拉信 + {@code send_msg} 回复。
 *
 * <p>{@code app_id}=CorpId，{@code app_secret}=微信客服 Secret；{@code extra.token} / {@code
 * encoding_aes_key} / {@code open_kfid}。
 */
public class WeixinKfChannelAdapter implements InboundChannelAdapter, InboundWebhookHandler {

  public static final String TYPE = "weixin_kf";

  private static final Logger log = LoggerFactory.getLogger(WeixinKfChannelAdapter.class);
  private static final String EXTRA_TOKEN = "token";
  private static final String EXTRA_AES_KEY = "encoding_aes_key";
  private static final String EXTRA_OPEN_KFID = "open_kfid";
  private static final String METHOD_GET = "get";
  private static final String EVENT_KF_MSG = "kf_msg_or_event";
  private static final String QUERY_MSG_SIGNATURE = "msg_signature";
  private static final String QUERY_TIMESTAMP = "timestamp";
  private static final String QUERY_NONCE = "nonce";
  private static final String QUERY_ECHOSTR = "echostr";
  private static final int HTTP_OK = 200;
  private static final int HTTP_BAD_REQUEST = 400;
  private static final int HTTP_UNAUTHORIZED = 401;
  private static final int MAX_SYNC_PAGES = 20;
  private static final String MEDIA_DIR_PREFIX = "weixin-kf";

  /** 同批合并媒体的 messageId 连接符（与 {@link #coalesceSameChatMedia} 一致）。 */
  private static final String MERGED_MSG_ID_JOIN = "+";

  private static final String MERGED_MSG_ID_SPLIT = "\\+";

  private final ChannelConfig config;
  private final ProfileRegistry profileRegistry;
  private final InboundMessageService inboundMessageService;
  private final OutboundGuard guard;
  private final Clock clock;
  private final WeixinKfReplySessionStore sessions = new WeixinKfReplySessionStore();
  private final Set<String> seenMsgIds = ConcurrentHashMap.newKeySet();
  private final Object syncLock = new Object();

  private volatile WeixinKfMsgCrypt crypt;
  private volatile WeixinKfClient api;
  private volatile WeixinKfEventNormalizer normalizer;
  private volatile WeixinKfInboundMediaResolver mediaResolver;
  private volatile String syncCursor = "";
  private volatile ChannelStatus.State state = ChannelStatus.State.DISCONNECTED;

  public WeixinKfChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard) {
    this(config, profileRegistry, inboundMessageService, guard, Clock.systemUTC());
  }

  WeixinKfChannelAdapter(
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

  /** 测试注入 API 客户端。 */
  WeixinKfChannelAdapter(
      ChannelConfig config,
      ProfileRegistry profileRegistry,
      InboundMessageService inboundMessageService,
      OutboundGuard guard,
      Clock clock,
      WeixinKfClient api,
      WeixinKfMsgCrypt crypt) {
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
    requireExtra(EXTRA_OPEN_KFID);
    if (profileRegistry.get(config.agent()).isEmpty()) {
      throw new IllegalArgumentException(
          "渠道 " + config.name() + " 绑定的 Agent " + config.agent() + " 不存在");
    }
    guard.check(WeixinKfApiClient.API_BASE);
    if (crypt == null) {
      crypt =
          new WeixinKfMsgCrypt(
              config.extra(EXTRA_TOKEN), config.extra(EXTRA_AES_KEY), config.appId());
    }
    if (api == null) {
      WeixinKfAccessTokenClient tokens =
          new WeixinKfAccessTokenClient(guard, config.appId(), config.appSecret());
      api = new WeixinKfApiClient(guard, tokens::getToken);
    }
    normalizer = new WeixinKfEventNormalizer(config.name());
    mediaResolver = new WeixinKfInboundMediaResolver(api, createMediaRoot(), config.name());
    syncCursor = loadSyncCursor();
    try {
      // 启动时排空积压：重启后 cursor 若过旧，不把历史 HI/图再丢进 Agent（否则刷屏并撞 5 条上限）
      drainSyncCursor(api, config.extra(EXTRA_OPEN_KFID));
    } catch (RuntimeException e) {
      log.warn("微信客服启动排空 sync 游标失败（将在首次回调时继续）: {}", sanitize(e.getMessage()));
    }
    state = ChannelStatus.State.CONNECTED;
  }

  @Override
  public synchronized void stop() {
    state = ChannelStatus.State.DISCONNECTED;
    normalizer = null;
    mediaResolver = null;
    seenMsgIds.clear();
    saveSyncCursor(syncCursor);
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(name(), TYPE, boundAgent(), state);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    WeixinKfClient client = api;
    if (client == null) {
      throw new IllegalStateException("渠道 " + name() + " 尚未启动");
    }
    WeixinKfReplySessionStore.Session session = sessions.requireForReply(chatId, clock.millis());
    WeixinKfChatTargets.Parsed target = WeixinKfChatTargets.parse(chatId);
    String configuredKf = config.extra(EXTRA_OPEN_KFID);
    if (configuredKf != null
        && !configuredKf.isBlank()
        && !configuredKf.equals(target.openKfid())) {
      throw new IllegalStateException("微信客服 chatId open_kfid 与配置不一致（chat=" + chatId + "）");
    }
    client.ensureAiReception(target.openKfid(), target.externalUserId());
    client.sendText(target.openKfid(), target.externalUserId(), text);
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
    WeixinKfMsgCrypt active = crypt;
    if (active == null) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "channel not started");
    }
    try {
      String plain =
          active.verifyUrl(
              request.query(QUERY_MSG_SIGNATURE),
              request.query(QUERY_TIMESTAMP),
              request.query(QUERY_NONCE),
              request.query(QUERY_ECHOSTR));
      return WebhookResponse.text(HTTP_OK, plain);
    } catch (Exception e) {
      log.warn("微信客服 URL 校验失败: {}", sanitize(e.getMessage()));
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "verify failed");
    }
  }

  private WebhookResponse handleCallback(WebhookRequest request) {
    WeixinKfMsgCrypt active = crypt;
    WeixinKfClient client = api;
    WeixinKfEventNormalizer activeNormalizer = normalizer;
    if (active == null || client == null || activeNormalizer == null) {
      return WebhookResponse.text(HTTP_BAD_REQUEST, "channel not started");
    }
    try {
      String encrypt = WeixinKfCallbackXml.cdataOrText(request.body(), "Encrypt");
      if (encrypt == null || encrypt.isBlank()) {
        return WebhookResponse.text(HTTP_BAD_REQUEST, "missing Encrypt");
      }
      String xml =
          active.decryptMsg(
              request.query(QUERY_MSG_SIGNATURE),
              request.query(QUERY_TIMESTAMP),
              request.query(QUERY_NONCE),
              encrypt);
      String event = WeixinKfCallbackXml.cdataOrText(xml, "Event");
      if (!EVENT_KF_MSG.equals(event)) {
        return WebhookResponse.text(HTTP_OK, "success");
      }
      String callbackToken = WeixinKfCallbackXml.cdataOrText(xml, "Token");
      String openKfid = WeixinKfCallbackXml.cdataOrText(xml, "OpenKfId");
      String configured = config.extra(EXTRA_OPEN_KFID);
      if (openKfid == null || openKfid.isBlank()) {
        openKfid = configured;
      } else if (configured != null && !configured.isBlank() && !configured.equals(openKfid)) {
        log.warn("微信客服回调 open_kfid 与配置不一致，忽略");
        return WebhookResponse.text(HTTP_OK, "success");
      }
      pullAndDispatch(client, activeNormalizer, openKfid, callbackToken);
      return WebhookResponse.text(HTTP_OK, "success");
    } catch (Exception e) {
      log.warn("微信客服回调处理失败: {}", sanitize(e.getMessage()));
      return WebhookResponse.text(HTTP_UNAUTHORIZED, "callback failed");
    }
  }

  private void pullAndDispatch(
      WeixinKfClient client,
      WeixinKfEventNormalizer activeNormalizer,
      String openKfid,
      String callbackToken) {
    synchronized (syncLock) {
      String cursor = syncCursor;
      boolean more = true;
      int pages = 0;
      List<InboundMessage> batch = new java.util.ArrayList<>();
      while (more && pages < MAX_SYNC_PAGES) {
        pages++;
        WeixinKfSyncResult page = client.syncMsg(openKfid, callbackToken, cursor);
        for (JsonNode item : page.messages()) {
          Optional<InboundMessage> message = activeNormalizer.normalize(item);
          if (message.isEmpty()) {
            continue;
          }
          InboundMessage m = message.get();
          if (!seenMsgIds.add(m.messageId())) {
            continue;
          }
          batch.add(m);
        }
        if (page.nextCursor() != null && !page.nextCursor().isBlank()) {
          cursor = page.nextCursor();
          syncCursor = cursor;
        }
        more = page.hasMore();
      }
      saveSyncCursor(syncCursor);
      for (InboundMessage m : coalesceSameChatMedia(batch)) {
        sessions.rememberInbound(m.chatId(), clock.millis());
        try {
          client.ensureAiReception(WeixinKfChatTargets.parse(m.chatId()).openKfid(), m.userId());
        } catch (RuntimeException e) {
          log.warn("微信客服确保智能助手接待失败: {}", sanitize(e.getMessage()));
        }
        dispatchOne(m);
      }
    }
  }

  /** 同一 sync 批次、同一会话的连续媒体合并为一条，避免 2 图 → 2 次闲聊并撞 5 条上限。 */
  static List<InboundMessage> coalesceSameChatMedia(List<InboundMessage> batch) {
    if (batch == null || batch.isEmpty()) {
      return List.of();
    }
    List<InboundMessage> out = new java.util.ArrayList<>();
    for (InboundMessage m : batch) {
      if (out.isEmpty()) {
        out.add(m);
        continue;
      }
      InboundMessage last = out.get(out.size() - 1);
      if (canMergeMedia(last, m)) {
        out.set(out.size() - 1, mergeMedia(last, m));
      } else {
        out.add(m);
      }
    }
    return out;
  }

  private static boolean canMergeMedia(InboundMessage a, InboundMessage b) {
    if (!a.chatId().equals(b.chatId())) {
      return false;
    }
    if (a.textual() || b.textual()) {
      return false;
    }
    if (a.attachments().isEmpty() || b.attachments().isEmpty()) {
      return false;
    }
    return (a.content() == null || a.content().isBlank())
        && (b.content() == null || b.content().isBlank());
  }

  private static InboundMessage mergeMedia(InboundMessage a, InboundMessage b) {
    List<InboundAttachment> merged = new java.util.ArrayList<>(a.attachments());
    merged.addAll(b.attachments());
    return new InboundMessage(
        a.channelType(),
        a.channelName(),
        a.messageId() + MERGED_MSG_ID_JOIN + b.messageId(),
        a.chatKind(),
        a.userId(),
        a.chatId(),
        "",
        false,
        a.mentionedBot() || b.mentionedBot(),
        merged);
  }

  /** 推进游标、不编排（启动排空 / 丢弃积压）。 */
  private void drainSyncCursor(WeixinKfClient client, String openKfid) {
    synchronized (syncLock) {
      String cursor = syncCursor;
      boolean more = true;
      int pages = 0;
      int discarded = 0;
      while (more && pages < MAX_SYNC_PAGES) {
        pages++;
        WeixinKfSyncResult page = client.syncMsg(openKfid, null, cursor);
        discarded += page.messages().size();
        for (JsonNode item : page.messages()) {
          String msgId = item.path("msgid").asText(null);
          if (msgId != null && !msgId.isBlank()) {
            seenMsgIds.add(msgId);
          }
        }
        if (page.nextCursor() != null && !page.nextCursor().isBlank()) {
          cursor = page.nextCursor();
          syncCursor = cursor;
        }
        more = page.hasMore();
      }
      saveSyncCursor(syncCursor);
      if (discarded > 0) {
        log.info(
            "微信客服渠道 {} 启动排空 sync 积压 {} 条（cursor 已推进，避免历史回放）", sanitize(config.name()), discarded);
      }
    }
  }

  private Path syncCursorFile() {
    return Path.of(".oryxos", "weixin-kf-sync-cursor-" + config.name() + ".txt");
  }

  private String loadSyncCursor() {
    try {
      Path file = syncCursorFile();
      if (!java.nio.file.Files.isRegularFile(file)) {
        return "";
      }
      String raw = java.nio.file.Files.readString(file).strip();
      return raw;
    } catch (Exception e) {
      log.warn("读取微信客服 sync cursor 失败: {}", sanitize(e.getMessage()));
      return "";
    }
  }

  private void saveSyncCursor(String cursor) {
    if (cursor == null) {
      return;
    }
    try {
      Path file = syncCursorFile();
      Path parent = file.getParent();
      if (parent != null) {
        java.nio.file.Files.createDirectories(parent);
      }
      java.nio.file.Files.writeString(file, cursor);
    } catch (Exception e) {
      log.warn("保存微信客服 sync cursor 失败: {}", sanitize(e.getMessage()));
    }
  }

  /** 对齐企微/飞书慢路径：需 {@code media/get} 时先 {@code beginSlowWork}（立刻「处理中」），落盘后再编排。 */
  private void dispatchOne(InboundMessage m) {
    CountDownLatch slowWork = null;
    try {
      if (!inboundMessageService.tryClaim(m.channelName(), m.messageId())) {
        log.info("渠道 {} 重复消息已忽略: {}", sanitize(m.channelName()), sanitize(m.messageId()));
        return;
      }
      // 合并消息 id 也分别占用，避免拆条重放
      if (m.messageId().contains(MERGED_MSG_ID_JOIN)) {
        for (String part : m.messageId().split(MERGED_MSG_ID_SPLIT)) {
          if (!part.isBlank()) {
            inboundMessageService.tryClaim(m.channelName(), part);
          }
        }
      }
      if (needsMediaDownload(m)) {
        slowWork = inboundMessageService.beginSlowWork(this, m.chatId(), null);
      }
      WeixinKfInboundMediaResolver resolver = mediaResolver;
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
      log.warn("微信客服消息分发失败（messageId={}）: {}", sanitize(m.messageId()), sanitize(e.getMessage()));
    }
  }

  private static boolean needsMediaDownload(InboundMessage message) {
    for (InboundAttachment attachment : message.attachments()) {
      if (isUnresolvedMediaAttachment(attachment)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isUnresolvedMediaAttachment(InboundAttachment attachment) {
    String type = attachment.type();
    if (!InboundAttachment.TYPE_IMAGE.equals(type)
        && !InboundAttachment.TYPE_FILE.equals(type)
        && !InboundAttachment.TYPE_AUDIO.equals(type)
        && !InboundAttachment.TYPE_VIDEO.equals(type)) {
      return false;
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return false;
    }
    return attachment.reference() != null && !attachment.reference().isBlank();
  }

  private Path createMediaRoot() {
    return InboundMediaRoots.forChannel(config.name(), MEDIA_DIR_PREFIX);
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
