package io.oryxos.core.agent;

/** 协作式取消：在 ReAct / 工具边界检测到取消请求后抛出，不表示工具执行失败。 */
public final class RunCancelledException extends RuntimeException {

  public RunCancelledException() {
    super("任务已取消");
  }
}
