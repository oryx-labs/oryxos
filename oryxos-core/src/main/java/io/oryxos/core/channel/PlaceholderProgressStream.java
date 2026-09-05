package io.oryxos.core.channel;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 无原地 PATCH 的 IM 进度流：占位 → 可选长 TTFT 心跳 → 至多一条工具行 → 终态（企微/钉钉）。
 *
 * <p>出站由 {@link ReplyFn} 完成，渠道侧只提供 sender 绑定。
 */
public final class PlaceholderProgressStream implements InboundProgressStream {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceholderProgressStream.class);

  public static final String DEFAULT_THINKING = "⏳ 正在思考…";
  public static final String DEFAULT_FAILED = "抱歉，这次处理失败了，请稍后重试或联系管理员。";
  public static final String DEFAULT_STOPPED = "⛔ 已停止";
  public static final String DEFAULT_HEARTBEAT = "⏳ 仍在处理…";

  /** 首 token / 工具 / 终态前多久发心跳；0=关闭。可用 {@code ORYXOS_IM_PROGRESS_HEARTBEAT_MS}。 */
  private static final long DEFAULT_HEARTBEAT_MS = 25_000L;

  /** 发送一条回复；失败应抛运行时异常。 */
  @FunctionalInterface
  public interface ReplyFn {
    void send(String chatId, String text, String replyToMessageId);
  }

  private final ReplyFn replyFn;
  private final String chatId;
  private final String replyToMessageId;
  private final String thinkingReply;
  private final String failedReply;
  private final String stoppedReply;
  private final String heartbeatReply;
  private final String logLabel;
  private final long heartbeatMs;
  private final AtomicBoolean finished = new AtomicBoolean(false);
  private volatile boolean toolNotified;
  private volatile boolean heartbeatSent;
  private ScheduledExecutorService heartbeatScheduler;
  private ScheduledFuture<?> heartbeatFuture;

  public PlaceholderProgressStream(
      ReplyFn replyFn, String chatId, String replyToMessageId, String logLabel) {
    this(
        replyFn,
        chatId,
        replyToMessageId,
        DEFAULT_THINKING,
        DEFAULT_FAILED,
        DEFAULT_STOPPED,
        DEFAULT_HEARTBEAT,
        resolveHeartbeatMs(),
        logLabel);
  }

  public PlaceholderProgressStream(
      ReplyFn replyFn,
      String chatId,
      String replyToMessageId,
      String thinkingReply,
      String failedReply,
      String logLabel) {
    this(
        replyFn,
        chatId,
        replyToMessageId,
        thinkingReply,
        failedReply,
        DEFAULT_STOPPED,
        DEFAULT_HEARTBEAT,
        resolveHeartbeatMs(),
        logLabel);
  }

  PlaceholderProgressStream(
      ReplyFn replyFn,
      String chatId,
      String replyToMessageId,
      String thinkingReply,
      String failedReply,
      String stoppedReply,
      String heartbeatReply,
      long heartbeatMs,
      String logLabel) {
    this.replyFn = replyFn;
    this.chatId = chatId;
    this.replyToMessageId = replyToMessageId;
    this.thinkingReply = thinkingReply == null ? DEFAULT_THINKING : thinkingReply;
    this.failedReply = failedReply == null ? DEFAULT_FAILED : failedReply;
    this.stoppedReply = stoppedReply == null ? DEFAULT_STOPPED : stoppedReply;
    this.heartbeatReply = heartbeatReply == null ? DEFAULT_HEARTBEAT : heartbeatReply;
    this.heartbeatMs = Math.max(0L, heartbeatMs);
    this.logLabel = logLabel == null ? "IM" : logLabel;
  }

  @Override
  public void start() {
    replyFn.send(chatId, thinkingReply, replyToMessageId);
    scheduleHeartbeat();
  }

  @Override
  public void onToken(String delta) {
    cancelHeartbeat();
  }

  @Override
  public void onToolStart(String toolName) {
    if (finished.get() || toolNotified) {
      return;
    }
    cancelHeartbeat();
    toolNotified = true;
    replyFn.send(chatId, "🔧 正在执行 `" + safeName(toolName) + "` …", replyToMessageId);
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    // no-op：避免多条刷屏
  }

  @Override
  public void finish(String finalText) {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    cancelHeartbeat();
    String body = finalText == null || finalText.isBlank() ? "（空回复）" : finalText;
    replyFn.send(chatId, body, replyToMessageId);
  }

  @Override
  public void fail(String errorMessage) {
    if (!finished.compareAndSet(false, true)) {
      return;
    }
    cancelHeartbeat();
    String body = resolveFailBody(errorMessage);
    try {
      replyFn.send(chatId, body, replyToMessageId);
    } catch (RuntimeException e) {
      LOG.warn("{}进度流失败态发送失败: {}", sanitize(logLabel), sanitize(e.getMessage()));
      throw e;
    }
  }

  private String resolveFailBody(String errorMessage) {
    if (errorMessage == null || errorMessage.isBlank()) {
      return failedReply;
    }
    String stripped = errorMessage.strip();
    if (looksLikeStopped(stripped)) {
      return stoppedReply;
    }
    return stripped;
  }

  private static boolean looksLikeStopped(String msg) {
    return msg.contains("已停止") || msg.contains("已取消") || msg.contains("中断");
  }

  private void scheduleHeartbeat() {
    if (heartbeatMs <= 0L) {
      return;
    }
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "oryxos-im-progress-hb");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    heartbeatScheduler = pool;
    heartbeatFuture =
        heartbeatScheduler.schedule(
            () -> {
              if (finished.get() || toolNotified || heartbeatSent) {
                return;
              }
              heartbeatSent = true;
              try {
                replyFn.send(chatId, heartbeatReply, replyToMessageId);
              } catch (RuntimeException e) {
                LOG.debug("{}进度心跳发送失败: {}", sanitize(logLabel), sanitize(e.getMessage()));
              }
            },
            heartbeatMs,
            TimeUnit.MILLISECONDS);
  }

  private void cancelHeartbeat() {
    ScheduledFuture<?> f = heartbeatFuture;
    if (f != null) {
      f.cancel(false);
    }
    ScheduledExecutorService s = heartbeatScheduler;
    if (s != null) {
      s.shutdownNow();
    }
    heartbeatFuture = null;
    heartbeatScheduler = null;
  }

  private static long resolveHeartbeatMs() {
    String raw = System.getenv("ORYXOS_IM_PROGRESS_HEARTBEAT_MS");
    if (raw == null || raw.isBlank()) {
      return DEFAULT_HEARTBEAT_MS;
    }
    try {
      return Long.parseLong(raw.strip());
    } catch (NumberFormatException e) {
      return DEFAULT_HEARTBEAT_MS;
    }
  }

  private static String safeName(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return "tool";
    }
    return toolName.replace('`', '\'').strip();
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
