package io.oryxos.core.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 中断管理器：支持用户通过 /stop 命令手动中断正在执行的 Agent 推理。
 *
 * <p>线程安全：使用 ConcurrentHashMap 存储中断标志，支持多线程并发访问。
 *
 * <p>使用场景：
 *
 * <ul>
 *   <li>用户发送 /stop 命令时，InboundMessageService 调用 {@link #interrupt(String)} 设置中断标志
 *   <li>ReActLoop 每轮迭代前调用 {@link #isInterrupted(String)} 检查是否需要中断
 *   <li>推理结束后调用 {@link #clear(String)} 清除中断标志
 * </ul>
 */
public class InterruptManager {

  private static final Logger LOG = LoggerFactory.getLogger(InterruptManager.class);

  private final Set<String> interruptedSessions = ConcurrentHashMap.newKeySet();

  /**
   * 设置指定 Session 的中断标志。
   *
   * @param sessionId Session ID
   */
  public void interrupt(String sessionId) {
    if (sessionId != null) {
      interruptedSessions.add(sessionId);
      LOG.info("设置中断标志: {}", sanitize(sessionId));
    }
  }

  /**
   * 检查指定 Session 是否已被中断。
   *
   * @param sessionId Session ID
   * @return true 如果已被中断
   */
  public boolean isInterrupted(String sessionId) {
    return sessionId != null && interruptedSessions.contains(sessionId);
  }

  /**
   * 清除指定 Session 的中断标志。
   *
   * @param sessionId Session ID
   */
  public void clear(String sessionId) {
    if (sessionId != null) {
      interruptedSessions.remove(sessionId);
      LOG.debug("清除中断标志: {}", sanitize(sessionId));
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
