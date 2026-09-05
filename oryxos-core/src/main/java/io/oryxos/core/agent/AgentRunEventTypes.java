package io.oryxos.core.agent;

/** 首版 Run Event 类型名，与流式工作台契约一致。 */
public final class AgentRunEventTypes {

  public static final String RUN_STARTED = "RUN_STARTED";
  public static final String STEP_STARTED = "STEP_STARTED";
  public static final String MESSAGE_CONTENT = "MESSAGE_CONTENT";
  public static final String TOOL_CALL_STARTED = "TOOL_CALL_STARTED";
  public static final String TOOL_CALL_FINISHED = "TOOL_CALL_FINISHED";
  public static final String STEP_FINISHED = "STEP_FINISHED";
  public static final String RUN_FINISHED = "RUN_FINISHED";
  public static final String RUN_FAILED = "RUN_FAILED";
  public static final String RUN_CANCELLING = "RUN_CANCELLING";
  public static final String RUN_CANCELLED = "RUN_CANCELLED";

  private AgentRunEventTypes() {}

  public static boolean isTerminal(String type) {
    return RUN_FINISHED.equals(type) || RUN_FAILED.equals(type) || RUN_CANCELLED.equals(type);
  }
}
