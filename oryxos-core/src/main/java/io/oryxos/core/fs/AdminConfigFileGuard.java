package io.oryxos.core.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 管理台热更配置只能经 AdminService 落盘——文件工具 / Workspace / download_file 直写会绕过校验与断连重连。
 *
 * <p>覆盖 {@code channels.yaml}（{@code ChannelAdminService}）与 {@code mcp_servers.yaml}（{@code
 * McpServerAdminService}）。
 *
 * <p>除词法路径段检查外，还会经 {@link RealPathBoundary} 解析已存在祖先的真实路径，避免 {@code alias.yaml → channels.yaml}
 * 这类软链绕过（对齐 {@code MemoryMdGuard}）。
 */
public final class AdminConfigFileGuard {

  private static final Set<String> RESERVED_LOWER = Set.of("channels.yaml", "mcp_servers.yaml");

  private AdminConfigFileGuard() {}

  public static void rejectMutation(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    rejectLexical(path);
    rejectResolved(Path.of(path));
  }

  /** 对已解析（常为绝对）路径做词法 + 真实路径双重检查；Workspace 等入口应在边界投影后再调。 */
  public static void rejectMutation(Path path) {
    if (path == null) {
      return;
    }
    rejectLexical(path.toString());
    rejectResolved(path);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Reserved filenames are ASCII; Locale.ROOT fold matches case-insensitive filesystems.")
  private static void rejectLexical(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    // 任意路径段命中即可：write_file("…/channels.yaml/x") 会 createDirectories 把配置名建成目录
    for (Path segment : Path.of(path)) {
      String lower = segment.toString().toLowerCase(Locale.ROOT);
      if (RESERVED_LOWER.contains(lower)) {
        throw new IllegalArgumentException("拒绝直接改写管理配置（请用 Channel / MCP 管理入口）: " + path);
      }
    }
  }

  private static void rejectResolved(Path path) {
    rejectSymlinkLeafTarget(path);
    Path projected;
    try {
      projected = RealPathBoundary.project(path).projectedReal();
    } catch (UncheckedIOException | IllegalArgumentException e) {
      return;
    }
    rejectLexical(projected.toString());
  }

  private static void rejectSymlinkLeafTarget(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.isSymbolicLink(absolute)) {
      return;
    }
    try {
      Path linkTarget = Files.readSymbolicLink(absolute);
      rejectLexical(linkTarget.toString());
      Path parent = absolute.getParent();
      if (parent != null) {
        rejectLexical(parent.resolve(linkTarget).normalize().toString());
      }
    } catch (IOException ignored) {
      // 读链失败则保守放行词法已通过的路径；真实写入仍受沙箱约束
    }
  }
}
