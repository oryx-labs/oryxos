package io.oryxos.core.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 虚拟线程内传播当前 Run ID，并记录跨线程的取消请求。
 *
 * <p>业务状态仍在 SQLite；ThreadLocal 只在一次执行期间有效，finally 必须 {@link #clear()}。
 */
public final class AgentRunExecutionContext {

  private static final ThreadLocal<Long> RUN_ID = new ThreadLocal<>();
  private static final Set<Long> CANCEL_REQUESTED = ConcurrentHashMap.newKeySet();

  private AgentRunExecutionContext() {}

  public static void set(long runId) {
    RUN_ID.set(runId);
  }

  public static Long currentRunId() {
    return RUN_ID.get();
  }

  public static void requestCancel(long runId) {
    CANCEL_REQUESTED.add(runId);
  }

  public static boolean isCancelRequested() {
    Long runId = RUN_ID.get();
    return runId != null && CANCEL_REQUESTED.contains(runId);
  }

  public static boolean isCancelRequested(long runId) {
    return CANCEL_REQUESTED.contains(runId);
  }

  public static void clear() {
    Long runId = RUN_ID.get();
    RUN_ID.remove();
    if (runId != null) {
      CANCEL_REQUESTED.remove(runId);
    }
  }

  public static void clearCancel(long runId) {
    CANCEL_REQUESTED.remove(runId);
  }
}
