package io.oryxos.channel.discord;

import io.oryxos.core.channel.InboundProgressStream;
import io.oryxos.core.channel.PlaceholderProgressStream;

/** Discord 进度流：REST 发消息无原地编辑，采用占位进度。 */
final class DiscordProgressStream implements InboundProgressStream {

  private final PlaceholderProgressStream delegate;

  DiscordProgressStream(DiscordMessageSender sender, String chatId, String replyToMessageId) {
    this.delegate =
        new PlaceholderProgressStream(sender::send, chatId, replyToMessageId, "Discord");
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
