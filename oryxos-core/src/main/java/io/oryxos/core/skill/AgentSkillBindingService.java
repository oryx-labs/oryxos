package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentMarkdown;
import io.oryxos.core.fs.RealPathBoundary;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Filesystem coordinator for the single-source-of-truth Agent-to-Skill symlink bindings. */
public class AgentSkillBindingService implements AgentSkillBindingReader {

  private static final Logger LOG = LoggerFactory.getLogger(AgentSkillBindingService.class);
  private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String AGENT_FILE = "AGENT.md";
  private static final String SKILL_FILE = "SKILL.md";
  private static final String LEGACY_SKILLS_FIELD = "skills";

  private final Path root;
  private final Path agentsDir;
  private final Path archiveDir;
  private final Path skillsDir;
  private final SkillMetadataReader metadataReader;

  public AgentSkillBindingService(Path oryxosRoot, SkillLoader ignoredLoader) {
    this(oryxosRoot, new SkillMetadataReader());
  }

  public AgentSkillBindingService(Path oryxosRoot, SkillMetadataReader metadataReader) {
    this.root = oryxosRoot.toAbsolutePath().normalize();
    this.agentsDir = root.resolve("agents");
    this.archiveDir = root.resolve("archive");
    this.skillsDir = root.resolve("skills");
    this.metadataReader = metadataReader;
  }

  /** Creates the fixed relative link; rebinding the same valid Skill is idempotent. */
  public synchronized AgentSkillBinding bind(String agentName, String skillName) {
    String agent = safe(agentName, "Agent");
    String skill = safe(skillName, "Skill");
    Path agentDir = requireAgent(agent);
    requireSkill(skill);
    Path linksDir = requireRealSkillsDir(agentDir);
    Path link = linksDir.resolve(skill);
    if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
      BoundSkillDescriptor existing =
          inspect(agent).bindings().stream()
              .filter(binding -> binding.name().equals(skill))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Agent Skill 绑定位置已被无效条目占用: " + link));
      return legacy(existing, agent);
    }
    try {
      Files.createDirectories(linksDir);
      Files.createSymbolicLink(link, expectedTarget(skill));
    } catch (IOException e) {
      throw new UncheckedIOException("创建 Agent Skill 绑定失败: " + agent + "/" + skill, e);
    }
    BoundSkillDescriptor created =
        inspect(agent).bindings().stream()
            .filter(binding -> binding.name().equals(skill))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("刚创建的 Skill 绑定未通过一致性校验: " + skill));
    return legacy(created, agent);
  }

  /** Deletes only a controlled fixed-target symlink; an absent binding is idempotent. */
  public synchronized void unbind(String agentName, String skillName) {
    String agent = safe(agentName, "Agent");
    String skill = safe(skillName, "Skill");
    Path link = requireRealSkillsDir(requireAgent(agent)).resolve(skill);
    if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    requireControlledLink(link, skill);
    try {
      Files.delete(link);
    } catch (IOException e) {
      throw new UncheckedIOException("解绑 Agent Skill 失败: " + agent + "/" + skill, e);
    }
  }

  /**
   * Atomically replaces an Agent's binding set from the caller's perspective, rolling back on I/O
   * failure.
   */
  public synchronized BindingInspection replaceBindings(
      String agentName, List<String> desiredSkills) {
    String agent = safe(agentName, "Agent");
    Path agentDir = requireAgent(agent);
    List<String> desired = normalizedNames(desiredSkills);
    desired.forEach(this::requireSkill);
    BindingInspection before = inspect(agent);
    if (before.issues().stream()
        .anyMatch(issue -> issue.type() != SkillBindingIssue.Type.STALE_REFERENCE)) {
      throw new IllegalArgumentException("Agent 存在损坏绑定，修复后才能整体替换: " + agent);
    }
    Set<String> current = new LinkedHashSet<>();
    before.bindings().forEach(binding -> current.add(binding.name()));
    if (current.equals(new LinkedHashSet<>(desired))) {
      return before;
    }
    Path linksDir = requireRealSkillsDir(agentDir);
    List<Path> created = new ArrayList<>();
    List<Move> removed = new ArrayList<>();
    try {
      Files.createDirectories(linksDir);
      for (String skill : desired) {
        if (!current.contains(skill)) {
          Path finalLink = linksDir.resolve(skill);
          Path temporary = linksDir.resolve("." + skill + ".tmp-" + UUID.randomUUID());
          Files.createSymbolicLink(temporary, expectedTarget(skill));
          moveAtomic(temporary, finalLink);
          created.add(finalLink);
        }
      }
      for (String skill : current) {
        if (!desired.contains(skill)) {
          Path link = linksDir.resolve(skill);
          requireControlledLink(link, skill);
          // Keep rollback artifacts outside skills/: that directory is the complete binding truth,
          // so a transaction-internal link must never be observable as a malformed binding.
          Path backup =
              agentDir.resolve(".skill-binding-remove-" + skill + "-" + UUID.randomUUID());
          moveAtomic(link, backup);
          removed.add(new Move(link, backup));
        }
      }
      BindingInspection after = inspect(agent);
      List<String> actual = after.bindings().stream().map(BoundSkillDescriptor::name).toList();
      boolean hasBindingIssue =
          after.issues().stream()
              .anyMatch(issue -> issue.type() != SkillBindingIssue.Type.STALE_REFERENCE);
      if (hasBindingIssue || !actual.equals(desired)) {
        throw new IllegalStateException("替换后的 Agent Skill 绑定未通过一致性校验: " + agent);
      }
      for (Move move : removed) {
        try {
          Files.deleteIfExists(move.temporary());
        } catch (IOException cleanupFailure) {
          LOG.warn("清理 Agent Skill 绑定备份失败: {}", sanitize(move.temporary().toString()));
        }
      }
      return after;
    } catch (IOException e) {
      rollbackReplace(created, removed);
      throw new UncheckedIOException("原子替换 Agent Skill 绑定失败: " + agent, e);
    } catch (RuntimeException e) {
      rollbackReplace(created, removed);
      throw e;
    }
  }

  /**
   * Returns whether a concrete installed directory exists. Symlink directories are intentionally
   * excluded, matching {@link SkillLoader#loadAll()}; malformed installed metadata remains a 400.
   */
  public boolean skillExists(String skillName) {
    String skill = safe(skillName, "Skill");
    Path directory = skillsDir.resolve(skill);
    return Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        && RealPathBoundary.isWithin(skillsDir, directory);
  }

  /**
   * 返回 Agent 的 skills/ 绑定目录，但先校验它不是一个越界符号链接（review 高危 5）。 skills/ 被替换成指向任意目录的软链接时，
   * bind/unbind/replaceBindings 会直接在那里面建/删链接——写操作前必须确认其真实路径仍落在 Agent 目录内。 目录不存在时投影到 Agent
   * 目录下的正常位置（真实路径仍在 agentDir 内），放行。
   */
  private Path requireRealSkillsDir(Path agentDir) {
    Path linksDir = agentDir.resolve("skills");
    if (Files.exists(linksDir, LinkOption.NOFOLLOW_LINKS)
        && !RealPathBoundary.isWithin(agentDir, linksDir)) {
      throw new IllegalArgumentException(
          "Agent skills/ 目录真实路径越界（疑似被替换为符号链接），拒绝操作: " + sanitize(linksDir.toString()));
    }
    return linksDir;
  }

  public List<AgentSkillBinding> validBindings(String agentName) {
    String agent = safe(agentName, "Agent");
    return inspect(agent).bindings().stream().map(binding -> legacy(binding, agent)).toList();
  }

  /** Returns a new stable snapshot every time; no Registry or cache participates. */
  @Override
  public BindingInspection inspect(String agentName) {
    String agent = safe(agentName, "Agent");
    Path agentDir = agentsDir.resolve(agent);
    if (!Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      return new BindingInspection(List.of(), List.of());
    }
    SkillBindingIssue.AgentState state =
        Files.isRegularFile(agentDir.resolve(AGENT_FILE))
            ? SkillBindingIssue.AgentState.ACTIVE
            : SkillBindingIssue.AgentState.INVALID;
    return inspectDirectory(agent, agentDir, state);
  }

  /** Finds every active and archived reference, including a controlled dangling link by name. */
  public synchronized List<SkillReference> references(String skillName) {
    String skill = safe(skillName, "Skill");
    List<SkillReference> references = new ArrayList<>();
    collectReferences(agentsDir, SkillReference.AgentState.ACTIVE, skill, references, false);
    collectReferences(archiveDir, SkillReference.AgentState.ARCHIVED, skill, references, true);
    return references.stream()
        .sorted(
            Comparator.comparing(SkillReference::agentName)
                .thenComparing(reference -> reference.state().name())
                .thenComparing(SkillReference::directoryName))
        .toList();
  }

  public synchronized <T> T archiveIfUnreferenced(
      String skillName, java.util.function.Supplier<T> archiveAction) {
    List<SkillReference> refs = references(skillName);
    if (!refs.isEmpty()) {
      throw new SkillReferencedException(skillName, refs);
    }
    return archiveAction.get();
  }

  /**
   * Runs a compound workspace mutation under the same reentrant lock as bind/archive operations.
   */
  public synchronized <T> T coordinate(java.util.function.Supplier<T> action) {
    return action.get();
  }

  /** Compatibility hook for older callers that have no result value. */
  public synchronized void deleteIfUnreferenced(String skillName, Runnable action) {
    archiveIfUnreferenced(
        skillName,
        () -> {
          action.run();
          return null;
        });
  }

  /** Scans active Agents, archived Agents and legacy frontmatter, excluding archive/skills. */
  public synchronized List<SkillBindingIssue> reconcile() {
    List<SkillBindingIssue> issues = new ArrayList<>();
    scanContainer(agentsDir, SkillBindingIssue.AgentState.ACTIVE, false, issues);
    scanContainer(archiveDir, SkillBindingIssue.AgentState.ARCHIVED, true, issues);
    return issues.stream()
        .sorted(
            Comparator.comparing(SkillBindingIssue::agentName)
                .thenComparing(issue -> issue.agentState().name())
                .thenComparing(SkillBindingIssue::entryName)
                .thenComparing(issue -> issue.type().name()))
        .toList();
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "Every untrusted issue string is passed through sanitize before logging.")
  public void logCurrentIssues() {
    for (SkillBindingIssue issue : reconcile()) {
      LOG.warn(
          "Agent Skill 绑定异常 [{}] {}/{}: {}",
          issue.type(),
          sanitize(issue.agentName()),
          sanitize(issue.entryName()),
          sanitize(issue.message()));
    }
  }

  private BindingInspection inspectDirectory(
      String agent, Path agentDir, SkillBindingIssue.AgentState state) {
    List<BoundSkillDescriptor> valid = new ArrayList<>();
    List<SkillBindingIssue> issues = new ArrayList<>();
    if (state == SkillBindingIssue.AgentState.INVALID) {
      Path linksDir = agentDir.resolve("skills");
      if (Files.isDirectory(linksDir, LinkOption.NOFOLLOW_LINKS)) {
        issues.add(
            issue(
                agent,
                state,
                "skills",
                linksDir,
                SkillBindingIssue.Type.STALE_REFERENCE,
                "Agent 目录缺少有效 AGENT.md"));
      }
      return new BindingInspection(valid, issues);
    }
    addLegacyIssue(agent, agentDir, state, issues);
    Path linksDir = agentDir.resolve("skills");
    if (!Files.isDirectory(linksDir, LinkOption.NOFOLLOW_LINKS)) {
      return new BindingInspection(valid, issues);
    }
    try (Stream<Path> entries = Files.list(linksDir)) {
      entries.sorted().forEach(entry -> inspectEntry(agent, state, entry, valid, issues));
    } catch (IOException e) {
      issues.add(
          issue(
              agent,
              state,
              "skills",
              linksDir,
              SkillBindingIssue.Type.INVALID_TARGET,
              "无法读取 Agent skills 目录: " + e.getMessage()));
    }
    return new BindingInspection(
        valid.stream().sorted(Comparator.comparing(BoundSkillDescriptor::name)).toList(), issues);
  }

  private void inspectEntry(
      String agent,
      SkillBindingIssue.AgentState state,
      Path entry,
      List<BoundSkillDescriptor> valid,
      List<SkillBindingIssue> issues) {
    String entryName = String.valueOf(entry.getFileName());
    if (!SAFE_NAME.matcher(entryName).matches() || !Files.isSymbolicLink(entry)) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              SkillBindingIssue.Type.INVALID_TARGET,
              "绑定项必须是安全命名的相对软连接"));
      return;
    }
    Path rawTarget = readLinkTarget(agent, state, entryName, entry, issues);
    if (rawTarget == null
        || !validateLexicalTarget(agent, state, entryName, entry, rawTarget, issues)) {
      return;
    }
    Path targetReal = resolveRealTarget(agent, state, entryName, entry, issues);
    if (targetReal == null
        || !validateTargetLayout(agent, state, entryName, entry, targetReal, issues)) {
      return;
    }
    inspectMetadata(agent, state, entryName, entry, targetReal, valid, issues);
  }

  private Path readLinkTarget(
      String agent,
      SkillBindingIssue.AgentState state,
      String entryName,
      Path entry,
      List<SkillBindingIssue> issues) {
    try {
      return Files.readSymbolicLink(entry);
    } catch (IOException e) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              SkillBindingIssue.Type.INVALID_TARGET,
              "无法读取软连接: " + e.getMessage()));
      return null;
    }
  }

  private boolean validateLexicalTarget(
      String agent,
      SkillBindingIssue.AgentState state,
      String entryName,
      Path entry,
      Path rawTarget,
      List<SkillBindingIssue> issues) {
    if (rawTarget.isAbsolute()) {
      issues.add(
          issue(agent, state, entryName, entry, SkillBindingIssue.Type.ESCAPED, "绑定必须使用相对软连接"));
      return false;
    }
    Path parent = entry.getParent();
    if (parent == null) {
      issues.add(
          issue(agent, state, entryName, entry, SkillBindingIssue.Type.INVALID_TARGET, "绑定项缺少父目录"));
      return false;
    }
    Path lexicalTarget = parent.resolve(rawTarget).toAbsolutePath().normalize();
    if (!lexicalTarget.startsWith(skillsDir) || !rawTarget.equals(expectedTarget(entryName))) {
      SkillBindingIssue.Type type;
      if (!lexicalTarget.startsWith(skillsDir)) {
        type = SkillBindingIssue.Type.ESCAPED;
      } else if (!entryName.equals(String.valueOf(rawTarget.getFileName()))) {
        type = SkillBindingIssue.Type.NAME_MISMATCH;
      } else {
        type = SkillBindingIssue.Type.INVALID_TARGET;
      }
      issues.add(
          issue(agent, state, entryName, entry, type, "绑定目标不是固定相对路径 ../../../skills/" + entryName));
      return false;
    }
    if (!Files.exists(entry)) {
      issues.add(issue(agent, state, entryName, entry, SkillBindingIssue.Type.DANGLING, "绑定目标不存在"));
      return false;
    }
    return true;
  }

  private Path resolveRealTarget(
      String agent,
      SkillBindingIssue.AgentState state,
      String entryName,
      Path entry,
      List<SkillBindingIssue> issues) {
    try {
      Path targetReal = entry.toRealPath();
      RealPathBoundary.requireWithin(skillsDir, targetReal);
      return targetReal;
    } catch (RuntimeException | IOException e) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              SkillBindingIssue.Type.ESCAPED,
              "绑定真实目标越过公共 Skill 根或无法解析"));
      return null;
    }
  }

  private boolean validateTargetLayout(
      String agent,
      SkillBindingIssue.AgentState state,
      String entryName,
      Path entry,
      Path targetReal,
      List<SkillBindingIssue> issues) {
    if (!Files.isDirectory(targetReal) || !Files.isRegularFile(targetReal.resolve(SKILL_FILE))) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              SkillBindingIssue.Type.INVALID_TARGET,
              "绑定目标不是含 SKILL.md 的目录"));
      return false;
    }
    if (!entryName.equals(String.valueOf(targetReal.getFileName()))) {
      issues.add(
          issue(
              agent,
              state,
              entryName,
              entry,
              SkillBindingIssue.Type.NAME_MISMATCH,
              "链接名与公共 Skill 目录名不一致"));
      return false;
    }
    return true;
  }

  private void inspectMetadata(
      String agent,
      SkillBindingIssue.AgentState state,
      String entryName,
      Path entry,
      Path targetReal,
      List<BoundSkillDescriptor> valid,
      List<SkillBindingIssue> issues) {
    try {
      SkillMetadataReader.Metadata metadata = metadataReader.read(targetReal);
      if (!entryName.equals(metadata.name())) {
        issues.add(
            issue(
                agent,
                state,
                entryName,
                entry,
                SkillBindingIssue.Type.NAME_MISMATCH,
                "链接名与 SKILL.md name 不一致"));
        return;
      }
      Path link = entry.toAbsolutePath().normalize();
      valid.add(
          new BoundSkillDescriptor(
              metadata.name(), metadata.description(), link, link.resolve(SKILL_FILE)));
    } catch (RuntimeException e) {
      SkillBindingIssue.Type type =
          e.getMessage() != null && e.getMessage().contains("name")
              ? SkillBindingIssue.Type.NAME_MISMATCH
              : SkillBindingIssue.Type.INVALID_TARGET;
      issues.add(issue(agent, state, entryName, entry, type, e.getMessage()));
    }
  }

  private void addLegacyIssue(
      String agent,
      Path agentDir,
      SkillBindingIssue.AgentState state,
      List<SkillBindingIssue> issues) {
    Path markdown = agentDir.resolve(AGENT_FILE);
    if (!Files.isRegularFile(markdown)) {
      return;
    }
    try {
      if (AgentMarkdown.split(Files.readString(markdown))
          .frontmatter()
          .containsKey(LEGACY_SKILLS_FIELD)) {
        issues.add(
            issue(
                agent,
                state,
                "skills",
                markdown,
                SkillBindingIssue.Type.STALE_REFERENCE,
                "AGENT.md 仍含旧版顶层 skills 字段"));
      }
    } catch (IOException | RuntimeException e) {
      issues.add(
          issue(
              agent,
              state,
              AGENT_FILE,
              markdown,
              SkillBindingIssue.Type.INVALID_TARGET,
              "无法读取 Agent 定义"));
    }
  }

  private void scanContainer(
      Path container,
      SkillBindingIssue.AgentState expectedState,
      boolean skipSkillArchive,
      List<SkillBindingIssue> issues) {
    if (!Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> dirs = Files.list(container)) {
      dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !skipSkillArchive || !"skills".equals(String.valueOf(path.getFileName())))
          .sorted()
          .forEach(
              dir -> {
                String name = String.valueOf(dir.getFileName());
                SkillBindingIssue.AgentState state =
                    Files.isRegularFile(dir.resolve(AGENT_FILE))
                        ? expectedState
                        : SkillBindingIssue.AgentState.INVALID;
                issues.addAll(inspectDirectory(name, dir, state).issues());
              });
    } catch (IOException e) {
      throw new UncheckedIOException("扫描 Agent Skill 绑定失败: " + container, e);
    }
  }

  private void collectReferences(
      Path container,
      SkillReference.AgentState state,
      String skill,
      List<SkillReference> output,
      boolean skipSkillArchive) {
    if (!Files.isDirectory(container, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> dirs = Files.list(container)) {
      dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !skipSkillArchive || !"skills".equals(String.valueOf(path.getFileName())))
          .sorted()
          .forEach(
              dir -> {
                Path link = dir.resolve("skills").resolve(skill);
                if (!Files.isSymbolicLink(link)) {
                  return;
                }
                try {
                  if (!Files.readSymbolicLink(link).equals(expectedTarget(skill))) {
                    return;
                  }
                } catch (IOException e) {
                  return;
                }
                String directoryName = String.valueOf(dir.getFileName());
                output.add(
                    new SkillReference(
                        agentName(dir, directoryName),
                        state,
                        directoryName,
                        link.toAbsolutePath().normalize()));
              });
    } catch (IOException e) {
      throw new UncheckedIOException("扫描 Skill 引用失败: " + container, e);
    }
  }

  private static String agentName(Path directory, String fallback) {
    Path file = directory.resolve(AGENT_FILE);
    if (!Files.isRegularFile(file)) {
      return fallback;
    }
    try {
      Object name = AgentMarkdown.split(Files.readString(file)).frontmatter().get("name");
      return name == null || String.valueOf(name).isBlank() ? fallback : String.valueOf(name);
    } catch (IOException | RuntimeException e) {
      return fallback;
    }
  }

  private Path requireAgent(String agent) {
    Path dir = agentsDir.resolve(agent);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
        || !Files.isRegularFile(dir.resolve(AGENT_FILE))) {
      throw new IllegalArgumentException("Agent 不存在或定义无效: " + agent);
    }
    RealPathBoundary.requireWithin(agentsDir, dir);
    return dir;
  }

  private Path requireSkill(String skill) {
    Path dir = skillsDir.resolve(skill);
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Skill 不存在: " + skill);
    }
    RealPathBoundary.requireWithin(skillsDir, dir);
    SkillMetadataReader.Metadata metadata = metadataReader.read(dir);
    if (!skill.equals(metadata.name())) {
      throw new IllegalArgumentException("Skill 名与目录不一致: " + skill);
    }
    return dir.toAbsolutePath().normalize();
  }

  private static List<String> normalizedNames(List<String> names) {
    if (names == null) {
      return List.of();
    }
    return names.stream().map(name -> safe(name, "Skill")).distinct().sorted().toList();
  }

  private static void requireControlledLink(Path link, String skill) {
    if (!Files.isSymbolicLink(link)) {
      throw new IllegalArgumentException("绑定位置不是软连接，拒绝删除: " + link);
    }
    try {
      if (!Files.readSymbolicLink(link).equals(expectedTarget(skill))) {
        throw new IllegalArgumentException("绑定不是受控固定链接，拒绝删除: " + link);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("读取绑定失败: " + link, e);
    }
  }

  private static Path expectedTarget(String skill) {
    return Path.of("..", "..", "..", "skills", skill);
  }

  private static void moveAtomic(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      throw new IOException("文件系统不支持原子移动: " + source, e);
    }
  }

  private static void rollbackReplace(List<Path> created, List<Move> removed) {
    for (Path path : created) {
      try {
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        // Keep the original failure; reconciliation will report any residue.
      }
    }
    for (int i = removed.size() - 1; i >= 0; i--) {
      Move move = removed.get(i);
      try {
        if (Files.exists(move.temporary(), LinkOption.NOFOLLOW_LINKS)) {
          moveAtomic(move.temporary(), move.original());
        }
      } catch (IOException ignored) {
        // Keep the original failure; reconciliation will report any residue.
      }
    }
  }

  private static AgentSkillBinding legacy(BoundSkillDescriptor descriptor, String agent) {
    return new AgentSkillBinding(
        agent,
        descriptor.name(),
        descriptor.description(),
        descriptor.linkPath(),
        descriptor.skillFile());
  }

  private static SkillBindingIssue issue(
      String agent,
      SkillBindingIssue.AgentState state,
      String entry,
      Path path,
      SkillBindingIssue.Type type,
      String message) {
    return new SkillBindingIssue(
        agent, state, entry, path.toAbsolutePath().normalize(), type, sanitize(message));
  }

  private static String safe(String name, String kind) {
    if (name == null || !SAFE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("非法 " + kind + " 名（只允许字母/数字/下划线/连字符）: " + name);
    }
    return name;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  private record Move(Path original, Path temporary) {}
}
