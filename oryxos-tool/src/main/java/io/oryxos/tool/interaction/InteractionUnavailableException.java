package io.oryxos.tool.interaction;

/** 当前渠道无法与人交互（如定时任务、Web 无状态调用）。 */
public class InteractionUnavailableException extends RuntimeException {

  public InteractionUnavailableException(String message) {
    super(message);
  }
}
