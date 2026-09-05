package io.oryxos.channel.feishu;

import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.channel.InboundProgressStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书进度流（#347）：交互卡片展示思考 → 工具 → 终态；token/工具回调节流 patch，避免频控。
 *
 * <p>首 token / 工具前定时 idle heartbeat，避免长 TTFT 卡面像假死。回调均在 ReAct 线程同步执行；{@link #start()} 失败时由适配器返回
 * empty 降级。
 */
final class FeishuStreamListener implements InboundProgressStream {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuStreamListener.class);

  private static final String TITLE_THINKING = "正在思考…";
  private static final String TITLE_WORKING = "处理中";
  private static final String TITLE_DONE = "回答";
  private static final String TITLE_FAILED = "处理失败";
  private static final String TITLE_STOPPED = "已停止";
  private static final String PLACEHOLDER = "_等待模型输出…_";
  private static final String IDLE_PLACEHOLDER = "_仍在等待模型输出…_";
  private static final long FLUSH_INTERVAL_MS = 2_000L;
  private static final int FLUSH_CHARS = 100;
  private static final int CARD_BODY_MAX = 8_000;
  static final Duration DEFAULT_IDLE_HEARTBEAT = Duration.ofSeconds(15);

  private final FeishuMessageSender sender;
  private final String chatId;
  private final String replyToMessageId;
  private final Duration idleHeartbeat;
  private final StringBuilder answer = new StringBuilder();
  private final List<String> toolLines = new ArrayList<>();

  private String messageId;
  private long lastFlushAt;
  private int charsSinceFlush;
  private boolean finished;
  private ScheduledExecutorService idleScheduler;
  private ScheduledFuture<?> idleTask;

  FeishuStreamListener(FeishuMessageSender sender, String chatId, String replyToMessageId) {
    this(sender, chatId, replyToMessageId, DEFAULT_IDLE_HEARTBEAT);
  }

  /** 测试可注入较短心跳间隔。 */
  FeishuStreamListener(
      FeishuMessageSender sender, String chatId, String replyToMessageId, Duration idleHeartbeat) {
    this.sender = sender;
    this.chatId = chatId;
    this.replyToMessageId = replyToMessageId;
    this.idleHeartbeat =
        idleHeartbeat == null || idleHeartbeat.isZero() || idleHeartbeat.isNegative()
            ? DEFAULT_IDLE_HEARTBEAT
            : idleHeartbeat;
  }

  @Override
  public void start() {
    String card =
        FeishuProgressCard.build(TITLE_THINKING, FeishuProgressCard.TEMPLATE_BLUE, PLACEHOLDER);
    messageId = sender.sendInteractive(chatId, card, replyToMessageId);
    lastFlushAt = System.currentTimeMillis();
    charsSinceFlush = 0;
    startIdleHeartbeat();
  }

  @Override
  public void onToken(String delta) {
    if (finished || delta == null || delta.isEmpty()) {
      return;
    }
    answer.append(delta);
    charsSinceFlush += delta.length();
    maybeFlush(false);
  }

  @Override
  public void onToolStart(String toolName) {
    if (finished) {
      return;
    }
    toolLines.add("🔧 正在执行 `" + safeName(toolName) + "` …");
    flush(TITLE_WORKING, FeishuProgressCard.TEMPLATE_BLUE, false);
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    if (finished) {
      return;
    }
    String mark = success ? "✅" : "❌";
    toolLines.add(mark + " 工具 `" + safeName(toolName) + "` " + (success ? "完成" : "失败"));
    flush(TITLE_WORKING, FeishuProgressCard.TEMPLATE_BLUE, false);
  }

  @Override
  public void finish(String finalText) {
    if (finished) {
      return;
    }
    finished = true;
    stopIdleHeartbeat();
    if (finalText != null && !finalText.isBlank()) {
      answer.setLength(0);
      answer.append(finalText);
    }
    flush(TITLE_DONE, FeishuProgressCard.TEMPLATE_GREEN, true);
  }

  @Override
  public void fail(String errorMessage) {
    if (finished) {
      return;
    }
    finished = true;
    stopIdleHeartbeat();
    String body =
        errorMessage == null || errorMessage.isBlank() ? "抱歉，这次处理失败了。" : errorMessage.strip();
    boolean stopped = ReActLoop.INTERRUPTED_REPLY.equals(body);
    String title = stopped ? TITLE_STOPPED : TITLE_FAILED;
    patchQuiet(FeishuProgressCard.build(title, FeishuProgressCard.TEMPLATE_RED, body), true);
  }

  private void startIdleHeartbeat() {
    stopIdleHeartbeat();
    ScheduledThreadPoolExecutor pool =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "feishu-progress-idle");
              t.setDaemon(true);
              return t;
            });
    pool.setRemoveOnCancelPolicy(true);
    idleScheduler = pool;
    long ms = idleHeartbeat.toMillis();
    idleTask = pool.scheduleAtFixedRate(this::idleTick, ms, ms, TimeUnit.MILLISECONDS);
  }

  private void stopIdleHeartbeat() {
    ScheduledFuture<?> task = idleTask;
    idleTask = null;
    if (task != null) {
      task.cancel(false);
    }
    ScheduledExecutorService scheduler = idleScheduler;
    idleScheduler = null;
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /** 仅在尚无模型/工具内容时刷新「仍在等待」，避免与 token 节流 patch 抢频控。 */
  private void idleTick() {
    if (finished || messageId == null) {
      return;
    }
    if (answer.length() > 0 || !toolLines.isEmpty()) {
      return;
    }
    patchQuiet(
        FeishuProgressCard.build(
            TITLE_THINKING, FeishuProgressCard.TEMPLATE_BLUE, IDLE_PLACEHOLDER),
        false);
    lastFlushAt = System.currentTimeMillis();
  }

  private void maybeFlush(boolean force) {
    long now = System.currentTimeMillis();
    if (!force && charsSinceFlush < FLUSH_CHARS && now - lastFlushAt < FLUSH_INTERVAL_MS) {
      return;
    }
    flush(TITLE_WORKING, FeishuProgressCard.TEMPLATE_BLUE, false);
  }

  private void flush(String title, String template, boolean terminal) {
    if (messageId == null) {
      return;
    }
    patchQuiet(FeishuProgressCard.build(title, template, buildBody()), terminal);
    lastFlushAt = System.currentTimeMillis();
    charsSinceFlush = 0;
  }

  private String buildBody() {
    StringBuilder body = new StringBuilder();
    if (!toolLines.isEmpty()) {
      for (String line : toolLines) {
        body.append(line).append('\n');
      }
      body.append('\n');
    }
    if (answer.length() == 0) {
      body.append(PLACEHOLDER);
    } else {
      body.append(truncate(answer.toString(), CARD_BODY_MAX));
    }
    return body.toString();
  }

  private void patchQuiet(String cardJson, boolean terminal) {
    try {
      sender.patchInteractive(messageId, cardJson);
    } catch (RuntimeException e) {
      if (terminal) {
        throw e;
      }
      LOG.warn("飞书进度卡片更新失败（继续推理）: {}", sanitize(e.getMessage()));
    }
  }

  private static String truncate(String text, int max) {
    if (text.length() <= max) {
      return text;
    }
    return text.substring(0, max) + "\n\n_（内容过长已截断）_";
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
