package io.oryxos.core.channel;

/** 长连接断线重连的指数退避（企微/钉钉共用）。 */
public final class ReconnectBackoff {

  private ReconnectBackoff() {}

  /**
   * @param attempt 从 0 起的失败次数
   * @param baseMs 基础间隔
   * @param maxMs 上限
   * @param maxShift 指数位移上限（避免溢出）
   */
  public static long delayMs(int attempt, long baseMs, long maxMs, int maxShift) {
    int capped = Math.min(Math.max(attempt, 0), maxShift);
    return Math.min(baseMs * (1L << capped), maxMs);
  }
}
