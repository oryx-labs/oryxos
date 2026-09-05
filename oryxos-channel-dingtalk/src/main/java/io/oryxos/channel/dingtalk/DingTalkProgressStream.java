package io.oryxos.channel.dingtalk;

import io.oryxos.core.channel.InboundProgressStream;
import io.oryxos.core.channel.PlaceholderProgressStream;

/** 钉钉进度流：sessionWebhook 出站无原地编辑，采用「占位 + 可选一次工具行 + 终态」（对称企微）。 */
final class DingTalkProgressStream implements InboundProgressStream {

  static final String THINKING_REPLY = PlaceholderProgressStream.DEFAULT_THINKING;
  static final String FAILED_REPLY = PlaceholderProgressStream.DEFAULT_FAILED;

  private final PlaceholderProgressStream delegate;

  DingTalkProgressStream(DingTalkMessageSender sender, String chatId, String replyToMessageId) {
    this.delegate = new PlaceholderProgressStream(sender::send, chatId, replyToMessageId, "钉钉");
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
