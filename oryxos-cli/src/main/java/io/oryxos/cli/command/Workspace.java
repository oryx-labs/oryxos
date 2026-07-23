package io.oryxos.cli.command;

import java.nio.file.Path;

/**
 * 轻命令（init / status / profile 不启动 Spring，图秒回）共用的工作区根解析。
 *
 * <p>解析顺序：系统属性 {@code -Doryxos.root} → 环境变量 {@code ORYXOS_ROOT} → 默认 {@code .oryxos}。与
 * serve/gateway 走的 Spring {@code oryxos.root} 对齐——Spring relaxed binding 同样把 {@code ORYXOS_ROOT} 绑到
 * {@code oryxos.root}，所以设一个环境变量两边都认。注意：{@code application.yml} 里的 {@code oryxos.root} 只对启动 Spring
 * 的命令生效，轻命令不读 yaml；要让轻命令也用自定义根，请用环境变量或系统属性。
 */
final class Workspace {

  static final String DEFAULT_ROOT = ".oryxos";

  private Workspace() {}

  /** 解析工作区根目录（可自定义路径与名字）。 */
  static Path root() {
    String sys = System.getProperty("oryxos.root");
    if (sys != null && !sys.isBlank()) {
      return Path.of(sys);
    }
    String env = System.getenv("ORYXOS_ROOT");
    if (env != null && !env.isBlank()) {
      return Path.of(env);
    }
    return Path.of(DEFAULT_ROOT);
  }
}
