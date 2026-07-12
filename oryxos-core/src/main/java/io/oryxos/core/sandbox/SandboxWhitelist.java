package io.oryxos.core.sandbox;

import java.util.List;

/**
 * 白名单的运行时管理契约：查询 / 增加 / 删除三类白名单条目。契约上移 oryxos-core（依赖倒置）—— oryxos-web 只依赖 core，管理端点通过本接口操作白名单；具体实现是
 * oryxos-tool 的 {@code WhitelistSandbox} （同一个实例既是校验墙 {@code Sandbox} 又是可管理白名单）。
 *
 * <p>安全提示：动态改白名单等于远程调整安全护栏。核心阶段 Web API 假设内网、无认证——暴露这组端点必须靠网络层 （内网隔离 /
 * 反向代理鉴权）兜底；每次改动实现方须落日志留痕。Profile 级 Tool Policy 与端点鉴权属扩展阶段。
 */
public interface SandboxWhitelist {

  /** 三类白名单：文件路径 / Shell 命令 / HTTP 域名。 */
  enum Category {
    FILE,
    SHELL,
    HTTP
  }

  /** 列出某类当前的全部白名单条目（文件类返回归一后的绝对路径）。 */
  List<String> list(Category category);

  /**
   * 增加一条白名单。幂等：已存在则不重复加。
   *
   * @return {@code true} 表示新增成功，{@code false} 表示条目已存在
   */
  boolean add(Category category, String value);

  /**
   * 删除一条白名单。
   *
   * @return {@code true} 表示确实删除了，{@code false} 表示条目原本就不在白名单里
   */
  boolean remove(Category category, String value);
}
