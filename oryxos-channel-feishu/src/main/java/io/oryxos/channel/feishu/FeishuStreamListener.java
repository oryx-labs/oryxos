package io.oryxos.channel.feishu;

import io.oryxos.core.agent.StreamListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书流式监听器：将 ReActLoop 的流式事件转化为飞书卡片的实时更新。
 *
 * <p>策略：
 *
 * <ul>
 *   <li>收到消息时：发送初始卡片（蓝色"正在思考"）
 *   <li>onToken：累积文本，每 2 秒或累积 100 字更新一次卡片
 *   <li>onToolStart/End：立即更新卡片（工具状态是关键节点）
 *   <li>完成时：更新卡片为最终结果（绿色"回答"或红色"失败"）
 * </ul>
 *
 * <p>线程安全：StreamListener 的回调在 ReActLoop 的处理线程上同步执行，无并发问题。
 */
public class FeishuStreamListener implements StreamListener {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuStreamListener.class);

  private static final int UPDATE_INTERVAL_MS = 2000; // 2秒更新一次
  private static final int UPDATE_THRESHOLD_CHARS = 100; // 或累积100字符

  private final FeishuMessageSender sender;
  private final FeishuCardBuilder cardBuilder;
  private final String chatId;
  private final String replyToMessageId;

  private String cardMessageId; // 卡片消息 ID

  private final StringBuilder tokenBuffer = new StringBuilder();
  private final List<String> thinkingProcess = new CopyOnWriteArrayList<>();
  private final List<String> activeTools = new CopyOnWriteArrayList<>();
  private final List<String> completedTools = new CopyOnWriteArrayList<>();

  private long lastUpdateTime = 0;
  private int tokensSinceLastUpdate = 0;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "sender/cardBuilder 是渠道适配器持有的单例，共享引用正是意图")
  public FeishuStreamListener(
      FeishuMessageSender sender,
      FeishuCardBuilder cardBuilder,
      String chatId,
      String replyToMessageId) {
    this.sender = sender;
    this.cardBuilder = cardBuilder;
    this.chatId = chatId;
    this.replyToMessageId = replyToMessageId;
  }

  /**
   * 启动流式监听：发送初始卡片。
   *
   * <p>必须在开始处理前调用（InboundMessageService 调用 agentService.process 之前）。
   */
  public void start() {
    // 发送初始卡片
    String initialCard = cardBuilder.buildInitialCard();
    cardMessageId = sender.sendCard(chatId, initialCard, replyToMessageId);

    lastUpdateTime = System.currentTimeMillis();
    LOG.debug("飞书流式监听启动 chatId={} cardMessageId={}", sanitize(chatId), sanitize(cardMessageId));
  }

  /**
   * 完成处理：更新卡片为最终结果。
   *
   * <p>必须在处理结束后调用（无论成功或失败）。
   *
   * @param finalAnswer 最终回答（成功时非空）
   * @param error 错误信息（失败时非空）
   */
  public void finish(String finalAnswer, String error) {
    try {
      // 更新卡片为最终状态
      if (cardMessageId != null) {
        String finalCard;
        if (error != null) {
          finalCard = cardBuilder.buildErrorCard(error);
        } else {
          finalCard = cardBuilder.buildCompletedCard(finalAnswer);
        }
        sender.updateCard(cardMessageId, finalCard);
      }

      LOG.debug("飞书流式监听完成 cardMessageId={}", sanitize(cardMessageId));
    } catch (Exception e) {
      LOG.error("飞书流式监听完成时异常: {}", sanitize(e.getMessage()));
    }
  }

  @Override
  public void onToken(String delta) {
    if (delta == null || delta.isEmpty()) {
      return;
    }

    tokenBuffer.append(delta);
    tokensSinceLastUpdate += delta.length();

    // 累积策略：时间间隔或字符数达到阈值时更新
    long now = System.currentTimeMillis();
    boolean timeToUpdate = (now - lastUpdateTime) >= UPDATE_INTERVAL_MS;
    boolean thresholdReached = tokensSinceLastUpdate >= UPDATE_THRESHOLD_CHARS;

    if (timeToUpdate || thresholdReached) {
      updateProcessingCard();
      lastUpdateTime = now;
      tokensSinceLastUpdate = 0;
    }
  }

  @Override
  public void onToolStart(String toolName) {
    activeTools.add(toolName);
    updateProcessingCard(); // 工具调用是关键节点，立即更新
    LOG.debug("工具开始: {}", sanitize(toolName));
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    activeTools.remove(toolName);
    if (success) {
      completedTools.add(toolName);
    }
    updateProcessingCard(); // 工具结束是关键节点，立即更新
    LOG.debug("工具结束: {} success={}", sanitize(toolName), success);
  }

  /** 更新"处理中"状态的卡片。 */
  private void updateProcessingCard() {
    if (cardMessageId == null) {
      return;
    }

    try {
      // 将当前 token buffer 作为最新的思考过程
      List<String> thinking = new ArrayList<>();
      if (tokenBuffer.length() > 0) {
        // 简单处理：取最后 200 字符作为"当前思考"
        int start = Math.max(0, tokenBuffer.length() - 200);
        String current = tokenBuffer.substring(start);
        thinking.add(current);
      }

      String card = cardBuilder.buildProcessingCard(thinking, activeTools, completedTools);
      sender.updateCard(cardMessageId, card);
    } catch (Exception e) {
      LOG.warn("更新飞书卡片失败: {}", sanitize(e.getMessage()));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
