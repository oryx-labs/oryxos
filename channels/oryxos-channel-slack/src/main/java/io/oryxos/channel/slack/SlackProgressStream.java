package io.oryxos.channel.slack;

import io.oryxos.core.channel.InboundProgressStream;
import io.oryxos.core.channel.PlaceholderProgressStream;

/** Slack 进度流：chat.postMessage 无原地编辑，采用「占位 + 可选一次工具行 + 终态」。 */
final class SlackProgressStream implements InboundProgressStream {

  private final PlaceholderProgressStream delegate;

  SlackProgressStream(SlackMessageSender sender, String chatId, String replyToMessageId) {
    this.delegate = new PlaceholderProgressStream(sender::send, chatId, replyToMessageId, "Slack");
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
