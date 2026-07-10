package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;

/**
 * "当前是哪个 Agent"的线程上下文：AgentService 入口 set、出口 finally clear，工具执行期间可取。
 *
 * <p>为什么用 ThreadLocal：{@code OryxTool.execute} 签名不带 Profile，但有些工具需要当前 Agent 配置 （如 19 节 notify 读
 * notify_channels）——改工具接口签名代价太大。虚拟线程每请求独立，天然不串； 但复用平台线程的场景下 finally 不清就会串号，所以 clear 必达（harness
 * 用异常路径钉死）。
 */
public final class ProfileContext {

  private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

  private ProfileContext() {}

  public static void set(Profile profile) {
    CURRENT.set(profile);
  }

  /** 未设置时返回 null。 */
  public static Profile current() {
    return CURRENT.get();
  }

  /** 用 remove() 而非 set(null)：防 ThreadLocal 泄漏（P3C 规约同款要求）。 */
  public static void clear() {
    CURRENT.remove();
  }
}
