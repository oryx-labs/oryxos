package io.oryxos.core.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 工作区保留路径写禁：与 {@code WorkspaceApiController} 对齐，堵住文件工具 / download_file 旁路。
 *
 * <p>路径匹配用段扫描（大小写不敏感），不依赖 {@code oryxos.root}——工具侧多为绝对路径。
 */
public final class WorkspaceMutationGuard {

  private static final String AGENTS = "agents";
  private static final String SKILLS = "skills";
  private static final String KNOWLEDGE = "knowledge";
  private static final String AGENT_MD = "agent.md";

  /** {@code agents/<name>/<kind|AGENT.md>}：相对 agents 下标再 +2。 */
  private static final int REL_AFTER_AGENT_NAME = 2;

  /** {@code agents/<name>/skills|knowledge/<leaf>}：相对 agents 下标再 +3。 */
  private static final int REL_BIND_LEAF = 3;

  /** 共享树段前若是 {@code agents/<name>/}，向前看 2 段对齐 agents。 */
  private static final int LOOKBACK_TO_AGENTS = 2;

  private WorkspaceMutationGuard() {}

  /**
   * 拒绝写入共享 Skill/Knowledge 实体或 Agent 绑定视图下的任意内容路径（共享 skills、agent 绑定 skills、共享 knowledge、agent 绑定
   * knowledge）。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Reserved path segments are ASCII; Locale.ROOT fold matches case-insensitive filesystems.")
  public static void rejectSkillKnowledgeContentWrite(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    List<String> segs = lowerSegments(path);
    if (underSharedTree(segs, SKILLS)
        || underSharedTree(segs, KNOWLEDGE)
        || underAgentBindTree(segs, SKILLS)
        || underAgentBindTree(segs, KNOWLEDGE)) {
      throw new IllegalArgumentException("拒绝写入 Skill/Knowledge 路径（请用管理台绑定入口）: " + path);
    }
  }

  /**
   * 拒绝文件工具直写 {@code agents/<name>/AGENT.md}——须走 {@code AgentLifecycleService.update} （校验 + 重注册
   * schedules）。
   *
   * <p>除词法检查外，经 {@link RealPathBoundary} 复检，避免 {@code notes.md → AGENT.md} 软链绕过。
   */
  public static void rejectAgentMdDirectWrite(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    rejectAgentMdLexical(path);
    rejectAgentMdResolved(Path.of(path));
  }

  /** 对已解析路径做词法 + 真实路径双重检查。 */
  public static void rejectAgentMdDirectWrite(Path path) {
    if (path == null) {
      return;
    }
    rejectAgentMdLexical(path.toString());
    rejectAgentMdResolved(path);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "AGENT.md is ASCII; Locale.ROOT fold matches case-insensitive filesystems.")
  private static void rejectAgentMdLexical(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    List<String> segs = lowerSegments(path);
    int agents = indexOf(segs, AGENTS);
    int fileIdx = agents + REL_AFTER_AGENT_NAME;
    if (agents < 0 || fileIdx >= segs.size()) {
      return;
    }
    // agents/<name>/AGENT.md 恰好三段（相对 agents）
    if (fileIdx == segs.size() - 1 && AGENT_MD.equals(segs.get(fileIdx))) {
      throw new IllegalArgumentException(
          "拒绝直接改写 AGENT.md，请通过 Agent 管理 / lifecycle.update: " + path);
    }
  }

  private static void rejectAgentMdResolved(Path path) {
    rejectAgentMdSymlinkLeaf(path);
    Path projected;
    try {
      projected = RealPathBoundary.project(path).projectedReal();
    } catch (UncheckedIOException | IllegalArgumentException e) {
      return;
    }
    rejectAgentMdLexical(projected.toString());
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "AGENT.md is ASCII; Locale.ROOT fold matches case-insensitive filesystems for symlink leaf names.")
  private static void rejectAgentMdSymlinkLeaf(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    if (!Files.isSymbolicLink(absolute)) {
      return;
    }
    try {
      Path linkTarget = Files.readSymbolicLink(absolute);
      Path fileName = linkTarget.getFileName();
      if (fileName != null && AGENT_MD.equals(fileName.toString().toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException(
            "拒绝直接改写 AGENT.md，请通过 Agent 管理 / lifecycle.update: " + path);
      }
      Path parent = absolute.getParent();
      if (parent != null) {
        rejectAgentMdLexical(parent.resolve(linkTarget).normalize().toString());
      }
    } catch (IOException ignored) {
      // 读链失败则保守放行词法已通过的路径
    }
  }

  /**
   * 拒绝 {@code make_dir} 占用绑定槽（agents 下 skills/knowledge 叶子及其子路径）。建成真目录后 bind 无法落软链；允许只建 skills 或
   * knowledge 父目录本身。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "Reserved path segments are ASCII; Locale.ROOT fold is intentional.")
  public static void rejectBindSlotCreate(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    List<String> segs = lowerSegments(path);
    int agents = indexOf(segs, AGENTS);
    int kindIdx = agents + REL_AFTER_AGENT_NAME;
    if (agents < 0 || kindIdx >= segs.size()) {
      return;
    }
    String kind = segs.get(kindIdx);
    if (!SKILLS.equals(kind) && !KNOWLEDGE.equals(kind)) {
      return;
    }
    // agents/<name>/skills|knowledge 之后还有段 → 会占叶子槽
    if (kindIdx < segs.size() - 1) {
      throw new IllegalArgumentException("拒绝在 Skill/Knowledge 绑定位建目录（请用 bind 入口）: " + path);
    }
  }

  /** 拒绝 {@code delete_file}/{@code move_file} 拆掉绑定叶子软链——须走 BindingService.unbind。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "Reserved path segments are ASCII; Locale.ROOT fold is intentional.")
  public static void rejectBindLinkDetach(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    if (isAgentBindLeaf(lowerSegments(path))) {
      throw new IllegalArgumentException("拒绝拆除 Skill/Knowledge 绑定链接（请用 unbind 入口）: " + path);
    }
  }

  /** agents/name/skills|knowledge/leaf：恰好 bind 叶子（无更深子路径）。 */
  private static boolean isAgentBindLeaf(List<String> segs) {
    int agents = indexOf(segs, AGENTS);
    int leafIdx = agents + REL_BIND_LEAF;
    if (agents < 0 || leafIdx != segs.size() - 1) {
      return false;
    }
    String kind = segs.get(agents + REL_AFTER_AGENT_NAME);
    return SKILLS.equals(kind) || KNOWLEDGE.equals(kind);
  }

  /** 根级共享库 skills/name/... 或 knowledge/name/... */
  private static boolean underSharedTree(List<String> segs, String rootName) {
    int i = indexOf(segs, rootName);
    if (i < 0 || i + 1 >= segs.size()) {
      return false;
    }
    // 若是 agents/<name>/skills|knowledge，则交给 underAgentBindTree
    if (i >= LOOKBACK_TO_AGENTS && AGENTS.equals(segs.get(i - LOOKBACK_TO_AGENTS))) {
      return false;
    }
    return true;
  }

  /** agents/name/skills|knowledge/... */
  private static boolean underAgentBindTree(List<String> segs, String kind) {
    int agents = indexOf(segs, AGENTS);
    int kindIdx = agents + REL_AFTER_AGENT_NAME;
    if (agents < 0 || kindIdx >= segs.size()) {
      return false;
    }
    return kind.equals(segs.get(kindIdx));
  }

  private static int indexOf(List<String> segs, String name) {
    for (int i = 0; i < segs.size(); i++) {
      if (name.equals(segs.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private static List<String> lowerSegments(String path) {
    List<String> segs = new ArrayList<>();
    for (Path segment : Path.of(path)) {
      segs.add(segment.toString().toLowerCase(Locale.ROOT));
    }
    return segs;
  }
}
