package io.oryxos.channel.feishu;

import io.oryxos.core.channel.InboundProgressStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书进度流（#347）：交互卡片展示思考 → 工具 → 终态；token/工具回调节流 patch，避免频控。
 *
 * <p>回调均在 ReAct 线程同步执行；{@link #start()} 失败时由适配器返回 empty 降级。
 */
final class FeishuStreamListener implements InboundProgressStream {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuStreamListener.class);

  private static final String TITLE_THINKING = "正在思考…";
  private static final String TITLE_WORKING = "处理中";
  private static final String TITLE_DONE = "回答";
  private static final String TITLE_FAILED = "处理失败";
  private static final String PLACEHOLDER = "_等待模型输出…_";
  private static final long FLUSH_INTERVAL_MS = 2_000L;
  private static final int FLUSH_CHARS = 100;
  private static final int CARD_BODY_MAX = 8_000;

  private final FeishuMessageSender sender;
  private final String chatId;
  private final String replyToMessageId;
  private final StringBuilder answer = new StringBuilder();
  private final List<String> toolLines = new ArrayList<>();

  private String messageId;
  private long lastFlushAt;
  private int charsSinceFlush;
  private boolean finished;

  FeishuStreamListener(FeishuMessageSender sender, String chatId, String replyToMessageId) {
    this.sender = sender;
    this.chatId = chatId;
    this.replyToMessageId = replyToMessageId;
  }

  @Override
  public void start() {
    String card =
        FeishuProgressCard.build(TITLE_THINKING, FeishuProgressCard.TEMPLATE_BLUE, PLACEHOLDER);
    messageId = sender.sendInteractive(chatId, card, replyToMessageId);
    lastFlushAt = System.currentTimeMillis();
    charsSinceFlush = 0;
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
    String body =
        errorMessage == null || errorMessage.isBlank() ? "抱歉，这次处理失败了。" : errorMessage.strip();
    patchQuiet(FeishuProgressCard.build(TITLE_FAILED, FeishuProgressCard.TEMPLATE_RED, body), true);
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
