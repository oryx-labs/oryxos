package io.oryxos.core.agent;

/** Run 终态稳定原因码：页面展示与对账共用，不依赖自由文本。 */
public final class AgentStopReasons {

  public static final String PROCESS_RESTARTED = "PROCESS_RESTARTED";
  public static final String NO_ACTIVE_WORKER = "NO_ACTIVE_WORKER";
  public static final String MAX_ITERATIONS = "MAX_ITERATIONS";

  public static final String MESSAGE_PROCESS_RESTARTED = "服务重启，任务执行已中断";
  public static final String MESSAGE_NO_ACTIVE_WORKER = "任务已取消";
  public static final String MESSAGE_MAX_ITERATIONS = ReActLoop.MAX_ITERATIONS_REPLY;

  private AgentStopReasons() {}
}
