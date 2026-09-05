package io.oryxos.channel.wecom;

import io.oryxos.core.channel.InboundProgressStream;
import io.oryxos.core.channel.PlaceholderProgressStream;

/**
 * 企微进度流：平台无消息 PATCH，采用「占位 markdown + 可选一次工具行 + 终态再发」（对齐飞书 {@code InboundProgressStream} 契约，但不逐
 * token 刷屏）。
 *
 * <p>{@link #start()} 成功后编排走流式路径并跳过延迟「处理中」文本，避免双提示。
 */
final class WeComProgressStream implements InboundProgressStream {

  static final String THINKING_REPLY = PlaceholderProgressStream.DEFAULT_THINKING;
  static final String FAILED_REPLY = PlaceholderProgressStream.DEFAULT_FAILED;

  private final PlaceholderProgressStream delegate;

  WeComProgressStream(WeComMessageSender sender, String chatId, String replyToMessageId) {
    this.delegate = new PlaceholderProgressStream(sender::send, chatId, replyToMessageId, "企微");
  }

  @Override
  public void start() {
    delegate.start();
  }

  @Override
  public void onToken(String delta) {
    delegate.onToken(delta);
  }

  @Override
  public void onToolStart(String toolName) {
    delegate.onToolStart(toolName);
  }

  @Override
  public void onToolEnd(String toolName, boolean success) {
    delegate.onToolEnd(toolName, success);
  }

  @Override
  public void finish(String finalText) {
    delegate.finish(finalText);
  }

  @Override
  public void fail(String errorMessage) {
    delegate.fail(errorMessage);
  }
}
