package io.oryxos.core.sandbox;

import java.util.List;

/**
 * Sandbox 白名单的持久化契约（依赖倒置放 core）：{@link io.oryxos.tool.sandbox.WhitelistSandbox} 启动时 {@link
 * #loadAll()} 恢复三类白名单、运行时增删写穿到这里，实现重启保留。JPA 实现在 oryxos-storage。
 *
 * <p>存的是"入内存的规范形"（FILE 类别为归一后的绝对路径字符串），由 WhitelistSandbox 在写穿前算好， 使 {@code list}/删除展示与库内值一致。{@code
 * add} 幂等（已存在返回 false）。
 */
public interface SandboxWhitelistStore {

  /** 全部已持久化的白名单条目（含三类）。 */
  List<Entry> loadAll();

  /** 增加一条（幂等）：已存在返回 {@code false}，实际新增返回 {@code true}。 */
  boolean add(SandboxWhitelist.Category category, String value);

  /** 删除一条：命中并删除返回 {@code true}，原本不存在返回 {@code false}。 */
  boolean remove(SandboxWhitelist.Category category, String value);

  /** 一条白名单持久化记录：类别 + 规范形取值。 */
  record Entry(SandboxWhitelist.Category category, String value) {}
}
