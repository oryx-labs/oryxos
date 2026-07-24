package io.oryxos.cli.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

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
  private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
  private static final List<String> RUNTIME_DIRECTORIES =
      List.of(
          "agents",
          "skills",
          "output",
          "memory",
          "sessions",
          "logs",
          ".staging/skill-import",
          "archive/.skills");

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

  /** Creates the complete runtime layout without accepting symlinked workspace-owned paths. */
  static Path initializeLayout() throws IOException {
    Path root = root().toAbsolutePath().normalize();
    createRealDirectory(root, "workspace root");
    for (String relative : RUNTIME_DIRECTORIES) {
      Path current = root;
      for (Path segment : Path.of(relative)) {
        current = current.resolve(segment);
        createRealDirectory(current, relative);
      }
      if (!current.normalize().startsWith(root)) {
        throw new IOException("Workspace directory escapes the root: " + relative);
      }
    }
    return root;
  }

  private static void createRealDirectory(Path directory, String safeName) throws IOException {
    if (Files.exists(directory, NOFOLLOW)) {
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Workspace path must be a real directory: " + safeName);
      }
      return;
    }
    Path parent = directory.getParent();
    if (parent != null
        && Files.exists(parent, NOFOLLOW)
        && (Files.isSymbolicLink(parent)
            || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))) {
      throw new IOException("Workspace parent must be a real directory: " + safeName);
    }
    Files.createDirectory(directory);
  }
}
