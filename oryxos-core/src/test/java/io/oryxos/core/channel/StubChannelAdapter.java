package io.oryxos.core.channel;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试桩渠道（017 US4 / SC-007 证据）：实现 {@link InboundChannelAdapter} 的内存收发桩—— 证明第二个入站渠道零 core
 * 修改即可接入，并作为契约测试集的桩档参数源。
 */
public class StubChannelAdapter implements InboundChannelAdapter {

  /** 一次已发送回复的记录。 */
  public record SentReply(String chatId, String text, String replyToMessageId) {}

  private final String name;
  private final String boundAgent;
  private final List<SentReply> sent = new CopyOnWriteArrayList<>();
  private volatile boolean started;
  private volatile RuntimeException sendFailure;
  private volatile InboundProgressStream progressStream;

  public StubChannelAdapter(String name, String boundAgent) {
    this.name = name;
    this.boundAgent = boundAgent;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public String type() {
    return "stub";
  }

  @Override
  public String boundAgent() {
    return boundAgent;
  }

  @Override
  public void start() {
    started = true;
  }

  @Override
  public void stop() {
    started = false;
  }

  @Override
  public ChannelStatus status() {
    return ChannelStatus.ok(
        name,
        type(),
        boundAgent,
        started ? ChannelStatus.State.CONNECTED : ChannelStatus.State.DISCONNECTED);
  }

  @Override
  public void sendReply(String chatId, String text, String replyToMessageId) {
    RuntimeException failure = sendFailure;
    if (failure != null) {
      throw failure;
    }
    sent.add(new SentReply(chatId, text, replyToMessageId));
  }

  /** 已发送回复的只读视图（供断言）。 */
  public List<SentReply> sent() {
    return Collections.unmodifiableList(sent);
  }

  /** 让后续 sendReply 抛出给定异常（模拟发送失败）。 */
  public void failSendsWith(RuntimeException failure) {
    this.sendFailure = failure;
  }

  @Override
  public java.util.Optional<InboundProgressStream> openProgressStream(
      String chatId, String replyToMessageId) {
    InboundProgressStream stream = progressStream;
    return stream == null ? java.util.Optional.empty() : java.util.Optional.of(stream);
  }

  /** 让后续 openProgressStream 返回给定进度流（模拟飞书卡片等平台可更新消息）。 */
  public void useProgressStream(InboundProgressStream stream) {
    this.progressStream = stream;
  }
}
