package io.oryxos.core.channel;

import io.oryxos.core.agent.StreamListener;

/**
 * 入站渠道可选的「进度流」：用平台可更新消息（如飞书交互卡片）展示思考/工具/终态。
 *
 * <p>生命周期：{@link #start()} → ReAct 中 {@link StreamListener} 回调 → {@link #finish(String)} 或 {@link
 * #fail(String)}。实现须在失败时尽量把卡片标红；{@code start} 失败时应让 {@link
 * InboundChannelAdapter#openProgressStream} 返回 empty，编排回落到整段 {@code sendReply}。
 */
public interface InboundProgressStream extends StreamListener {

  /** 发送初始「思考中」占位（同步）。 */
  void start();

  /** 推理成功：写入最终答案并标为完成态。 */
  void finish(String finalText);

  /** 推理失败：写入错误说明并标为失败态。 */
  void fail(String errorMessage);
}
