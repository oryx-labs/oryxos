package io.oryxos.core.agent;

import io.oryxos.core.skill.AgentSkillCoordinator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Agent 目录的文件读写，限定在 {@code .oryxos/} 内（第 30 节）。
 *
 * <p>write：把一段 {@code AGENT.md} 写进 {@code .oryxos/agents/<name>/}；delete：回滚删已写目录； archive：把整个 Agent
 * 目录移进 {@code .oryxos/archive/}（删除不物理删，定义可追溯）。 name 必须是安全目录段（防路径穿越）。
 */
public class AgentStore {

  private static final String AGENT_FILE = "AGENT.md";
  private static final String SKILLS_DIRECTORY = "skills";
  private static final String RESERVED_SKILL_PREFIX = ".oryxos-";
  private static final String BACKUP_FILE_PREFIX = ".oryxos-backup-";
  private static final Logger LOG = LoggerFactory.getLogger(AgentStore.class);

  private final Path oryxosRoot;
  private final Path agentsDir;
  private final Path archiveDir;
  private final AgentSkillCoordinator skillCoordinator;
  private final AtomicMover atomicMover;

  public AgentStore(Path oryxosRoot) {
    this(oryxosRoot, null);
  }

  /** 生产构造器：所有 Agent 目录变更与请求期 Skill 读租约共用同一协调器。 */
  public AgentStore(Path oryxosRoot, AgentSkillCoordinator skillCoordinator) {
    this(oryxosRoot, skillCoordinator, AgentStore::moveAtomically);
  }

  AgentStore(Path oryxosRoot, AgentSkillCoordinator skillCoordinator, AtomicMover atomicMover) {
    this.oryxosRoot = oryxosRoot.toAbsolutePath().normalize();
    this.agentsDir = this.oryxosRoot.resolve("agents");
    this.archiveDir = this.oryxosRoot.resolve("archive");
    this.skillCoordinator = skillCoordinator;
    this.atomicMover = atomicMover;
  }

  /** 写 .oryxos/agents/&lt;name&gt;/AGENT.md，返回该 Agent 目录。 */
  public Path write(String name, String agentMarkdown) {
    AgentName agentName = AgentName.parse(name);
    return mutate(agentName, () -> writeUnlocked(agentName, agentMarkdown));
  }

  private Path writeUnlocked(AgentName agentName, String agentMarkdown) {
    Path dir = agentsDir.resolve(agentName.value());
    try {
      requireExactIdentityOrAbsent(agentName);
      createWorkspaceDirectoriesSafely(dir);
      ensureNoSymbolicLinks(agentsDir, dir);
      atomicWrite(dir.resolve(AGENT_FILE), agentMarkdown);
    } catch (IOException e) {
      throw new UncheckedIOException("写入 Agent 目录失败: " + agentName, e);
    }
    return dir;
  }

  /** Reads one Agent definition after applying the same identity and symlink checks as writes. */
  String readAgentMarkdown(String name) {
    AgentName agentName = AgentName.parse(name);
    Path dir = agentsDir.resolve(agentName.value());
    Path file = dir.resolve(AGENT_FILE);
    try {
      requireExactIdentityOrAbsent(agentName);
      ensureNoSymbolicLinks(agentsDir, file);
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("Agent 定义不存在: " + agentName);
      }
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取 Agent 定义失败: " + agentName, e);
    }
  }

  /**
   * 脚手架式写入整个 Agent 目录：{@code files} 的键是相对 Agent 目录的路径（如 {@code AGENT.md}、{@code
   * scripts/example.py}），值是文件内容。每个路径 normalize 后必须落在该 Agent 目录内（防穿越）。返回该 Agent 目录。
   */
  public Path writeAll(String name, Map<String, String> files) {
    AgentName agentName = AgentName.parse(name);
    validateNoReservedSkillPaths(files == null ? null : files.keySet());
    return mutate(agentName, () -> writeAllUnlocked(agentName, files, false));
  }

  /** Creates a brand-new Agent directory and never reuses or overwrites an existing case alias. */
  public Path createAll(String name, Map<String, String> files) {
    AgentName agentName = AgentName.parse(name);
    validateNoReservedSkillPaths(files == null ? null : files.keySet());
    return mutate(agentName, () -> writeAllUnlocked(agentName, files, true));
  }

  private Path writeAllUnlocked(
      AgentName agentName, Map<String, String> files, boolean requireNewDirectory) {
    Path dir = agentsDir.resolve(agentName.value()).normalize();
    Map<Path, String> targets = resolveTargets(dir, files);
    List<StagedWrite> stagedWrites = new ArrayList<>();
    Set<Path> createdDirectories = new LinkedHashSet<>();
    int attemptedWrites = 0;
    try {
      validateWriteTargets(targets.keySet());
      if (requireNewDirectory) {
        createWorkspaceDirectoriesSafely(agentsDir);
        if (agentIdentityExists(agentName.value())) {
          throw new IllegalArgumentException("Agent 已存在或存在大小写别名: " + agentName);
        }
        try {
          Files.createDirectory(dir);
        } catch (FileAlreadyExistsException error) {
          throw new IllegalArgumentException("Agent 已存在或存在大小写别名: " + agentName, error);
        }
        createdDirectories.add(dir);
      } else {
        requireExactIdentityOrAbsent(agentName);
        createWorkspaceDirectoriesSafely(oryxosRoot);
      }
      stageWrites(dir, targets, createdDirectories, stagedWrites);
      for (StagedWrite stagedWrite : stagedWrites) {
        ensureNoSymbolicLinks(agentsDir, stagedWrite.target());
        Path targetParent =
            Objects.requireNonNull(stagedWrite.target().getParent(), "staged target parent");
        ensureRealPathContained(targetParent);
        attemptedWrites++;
        atomicMover.move(stagedWrite.staged(), stagedWrite.target());
      }
    } catch (IOException e) {
      rollbackStagedWrites(stagedWrites, attemptedWrites, createdDirectories, e);
      throw new UncheckedIOException("写入 Agent 目录失败: " + agentName, e);
    } catch (RuntimeException e) {
      rollbackStagedWrites(stagedWrites, attemptedWrites, createdDirectories, e);
      throw e;
    }
    cleanupCommittedArtifactsBestEffort(stagedWrites);
    return dir;
  }

  /** 原子写单个 Agent 相对文件；Workspace 的受管 Skill 写入复用此入口。 */
  public Path writeFile(String name, String relativePath, String content) {
    AgentName agentName = AgentName.parse(name);
    validateNoReservedSkillPath(relativePath);
    return mutate(
        agentName,
        () -> {
          Path dir = agentsDir.resolve(agentName.value()).normalize();
          Path target = resolveTarget(dir, relativePath);
          try {
            requireExactIdentityOrAbsent(agentName);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
              throw new IllegalArgumentException("Agent 目录不存在: " + agentName);
            }
            ensureNoSymbolicLinks(agentsDir, dir);
            atomicWrite(target, content);
            return target;
          } catch (IOException e) {
            throw new UncheckedIOException("写入 Agent 文件失败: " + agentName, e);
          }
        });
  }

  /** 递归删除一个 Agent 目录（create 中途失败回滚用）。 */
  public void delete(Path agentDir) {
    Path normalized = agentDir == null ? null : agentDir.normalize();
    Path parent = normalized == null ? null : normalized.getParent();
    if (normalized == null || parent == null || !parent.equals(agentsDir.normalize())) {
      throw new IllegalArgumentException("待删除路径不是直接 Agent 目录");
    }
    AgentName agentName = AgentName.fromDirectory(normalized);
    mutate(
        agentName,
        () -> {
          deleteUnlocked(normalized);
          return null;
        });
  }

  private void deleteUnlocked(Path agentDir) {
    if (!Files.exists(agentDir)) {
      return;
    }
    try {
      ensureNoSymbolicLinks(agentsDir, agentDir);
    } catch (IOException e) {
      throw new UncheckedIOException("检查 Agent 目录失败: " + agentDir.getFileName(), e);
    }
    try (Stream<Path> walk = Files.walk(agentDir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(AgentStore::deleteOne);
    } catch (IOException e) {
      throw new UncheckedIOException("删除 Agent 目录失败: " + agentDir.getFileName(), e);
    }
  }

  /** 整个 Agent 目录移入 .oryxos/archive/（不物理删）；目标已存在则加时间戳后缀避免覆盖。 */
  public void archive(String name) {
    AgentName agentName = AgentName.parse(name);
    mutate(
        agentName,
        () -> {
          archiveUnlocked(agentName);
          return null;
        });
  }

  private void archiveUnlocked(AgentName agentName) {
    Path src = agentsDir.resolve(agentName.value());
    try {
      requireExactIdentityOrAbsent(agentName);
      createWorkspaceDirectoriesSafely(archiveDir);
      ensureNoSymbolicLinks(agentsDir, src);
      ensureNoSymbolicLinks(archiveDir, archiveDir);
      Path dst = archiveDir.resolve(agentName.value());
      if (Files.exists(dst)) {
        dst = archiveDir.resolve(agentName.value() + "-" + System.currentTimeMillis());
      }
      Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException("归档 Agent 目录失败: " + agentName, e);
    }
  }

  private static void deleteOne(Path path) {
    try {
      Files.delete(path);
    } catch (IOException e) {
      throw new UncheckedIOException("删除失败: " + path.getFileName(), e);
    }
  }

  private <T> T mutate(AgentName agentName, Supplier<T> operation) {
    if (skillCoordinator == null) {
      return operation.get();
    }
    return skillCoordinator.mutate(agentName.value(), operation::get);
  }

  /** True for either an exact existing Agent or any case-folded filesystem alias. */
  boolean agentIdentityExists(String name) {
    AgentName requested = AgentName.parse(name);
    try {
      return scanIdentity(requested, false);
    } catch (IOException error) {
      throw new UncheckedIOException("检查 Agent 目录身份失败: " + requested, error);
    }
  }

  private void requireExactIdentityOrAbsent(AgentName requested) throws IOException {
    scanIdentity(requested, true);
  }

  private boolean scanIdentity(AgentName requested, boolean rejectAlias) throws IOException {
    if (!Files.isDirectory(agentsDir, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    boolean found = false;
    try (DirectoryStream<Path> children = Files.newDirectoryStream(agentsDir)) {
      for (Path child : children) {
        AgentName existing;
        try {
          existing = AgentName.fromDirectory(child);
        } catch (IllegalArgumentException ignored) {
          continue;
        }
        if (!existing.lockKey().equals(requested.lockKey())) {
          continue;
        }
        found = true;
        if (rejectAlias && !existing.value().equals(requested.value())) {
          throw new IllegalArgumentException("Agent 名与现有目录存在大小写冲突: " + requested.value());
        }
      }
    }
    return found;
  }

  private static Map<Path, String> resolveTargets(Path agentDir, Map<String, String> files) {
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("Agent 文件集合为空");
    }
    Map<Path, String> targets = new LinkedHashMap<>();
    Set<String> canonicalTargets = new LinkedHashSet<>();
    for (Map.Entry<String, String> entry : files.entrySet()) {
      Path target = resolveTarget(agentDir, entry.getKey());
      String canonicalTarget = canonicalRelativePath(agentDir, target);
      if (!canonicalTargets.add(canonicalTarget)
          || targets.putIfAbsent(target, entry.getValue()) != null) {
        throw new IllegalArgumentException("重复文件路径: " + entry.getKey());
      }
    }
    return targets;
  }

  /**
   * Produces one portable identity for an Agent-relative path. Normalizing and folding each segment
   * separately prevents aliases from collapsing only after deployment to a case-insensitive or
   * Unicode-normalizing filesystem.
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Untrusted path segments are intentionally NFC-normalized and case-folded only to form a conservative collision key; the transformed value is never used as a filesystem path or persisted in place of the original name")
  private static String canonicalRelativePath(Path agentDir, Path target) {
    StringBuilder key = new StringBuilder();
    for (Path segment : agentDir.relativize(target)) {
      if (!key.isEmpty()) {
        key.append('/');
      }
      String normalized = Normalizer.normalize(segment.toString(), Normalizer.Form.NFC);
      key.append(normalized.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT));
    }
    return key.toString();
  }

  /** Agent 文件 API 不得改写由 Skill 管理服务独占维护的控制 sidecar。 */
  static void validateNoReservedSkillPaths(Iterable<String> relativePaths) {
    if (relativePaths == null) {
      return;
    }
    for (String relativePath : relativePaths) {
      validateNoReservedSkillPath(relativePath);
    }
  }

  private static void validateNoReservedSkillPath(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      return;
    }
    Path relative = Path.of(relativePath).normalize();
    if (relative.isAbsolute()
        || relative.getNameCount() < 3
        || !equalsAsciiIgnoreCase(relative.getName(0).toString(), SKILLS_DIRECTORY)) {
      return;
    }
    for (int index = 2; index < relative.getNameCount(); index++) {
      String segment = relative.getName(index).toString();
      if (startsWithAsciiIgnoreCase(segment, RESERVED_SKILL_PREFIX)) {
        throw new IllegalArgumentException("受保护的 Skill 控制文件不能通过 Agent 文件 API 写入");
      }
    }
  }

  private static boolean equalsAsciiIgnoreCase(String value, String expectedLowercaseAscii) {
    return value.length() == expectedLowercaseAscii.length()
        && startsWithAsciiIgnoreCase(value, expectedLowercaseAscii);
  }

  private static boolean startsWithAsciiIgnoreCase(String value, String expectedLowercaseAscii) {
    if (value.length() < expectedLowercaseAscii.length()) {
      return false;
    }
    for (int index = 0; index < expectedLowercaseAscii.length(); index++) {
      char actual = value.charAt(index);
      if (actual >= 'A' && actual <= 'Z') {
        actual = (char) (actual + ('a' - 'A'));
      }
      if (actual != expectedLowercaseAscii.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  private static Path resolveTarget(Path agentDir, String relativePath) {
    if (relativePath == null
        || relativePath.isBlank()
        || relativePath.indexOf('\\') >= 0
        || relativePath.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("非法文件路径: " + relativePath);
    }
    Path relative = Path.of(relativePath);
    Path target = agentDir.resolve(relative).normalize();
    if (relative.isAbsolute() || target.equals(agentDir) || !target.startsWith(agentDir)) {
      throw new IllegalArgumentException("非法文件路径: " + relativePath);
    }
    return target;
  }

  private void atomicWrite(Path target, String content) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("目标文件缺少父目录");
    }
    createWorkspaceDirectoriesSafely(parent);
    ensureNoSymbolicLinks(agentsDir, target);
    ensureRealPathContained(parent);
    Path temp = Files.createTempFile(parent, ".oryxos-write-", ".tmp");
    IOException failure = null;
    try {
      Files.writeString(temp, content == null ? "" : content);
      ensureNoSymbolicLinks(agentsDir, target);
      ensureRealPathContained(parent);
      atomicMover.move(temp, target);
    } catch (IOException e) {
      failure = e;
      throw e;
    } finally {
      try {
        Files.deleteIfExists(temp);
      } catch (IOException cleanupError) {
        if (failure != null) {
          failure.addSuppressed(cleanupError);
        } else {
          throw cleanupError;
        }
      }
    }
  }

  private void validateWriteTargets(Iterable<Path> targets) throws IOException {
    for (Path target : targets) {
      ensureNoSymbolicLinks(agentsDir, target);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
          && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("目标文件不是普通文件: " + target.getFileName());
      }
    }
  }

  private void stageWrites(
      Path agentDirectory,
      Map<Path, String> targets,
      Set<Path> createdDirectories,
      List<StagedWrite> stagedWrites)
      throws IOException {
    for (Map.Entry<Path, String> entry : targets.entrySet()) {
      Path target = entry.getKey();
      Path parent = target.getParent();
      if (parent == null) {
        throw new IllegalArgumentException("目标文件缺少父目录");
      }
      createTargetDirectories(agentDirectory, parent, createdDirectories);
      ensureNoSymbolicLinks(agentsDir, target);
      ensureRealPathContained(parent);

      boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
      if (existed && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalArgumentException("目标文件不是普通文件: " + target.getFileName());
      }
      Path staged = Files.createTempFile(parent, ".oryxos-write-", ".tmp");
      Path backup = null;
      try {
        Files.writeString(staged, entry.getValue() == null ? "" : entry.getValue());
        if (existed) {
          // Keep the recovery copy beside its target. An atomic rollback then remains on the same
          // FileStore even when an Agent subtree is a legitimate separate mount.
          backup = Files.createTempFile(parent, BACKUP_FILE_PREFIX, ".tmp");
          Files.copy(
              target,
              backup,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES);
        }
        stagedWrites.add(new StagedWrite(target, staged, backup));
      } catch (IOException | RuntimeException error) {
        deleteArtifact(staged, error);
        deleteArtifact(backup, error);
        throw error;
      }
    }
  }

  private void createTargetDirectories(
      Path agentDirectory, Path parent, Set<Path> createdDirectories) throws IOException {
    Path current = parent;
    while (current != null && current.startsWith(agentDirectory)) {
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        createdDirectories.add(current);
      }
      if (current.equals(agentDirectory)) {
        break;
      }
      current = current.getParent();
    }
    createWorkspaceDirectoriesSafely(parent);
  }

  private static void rollbackStagedWrites(
      List<StagedWrite> stagedWrites,
      int attemptedWrites,
      Set<Path> createdDirectories,
      Throwable originalFailure) {
    boolean rollbackComplete = true;
    Set<Path> recoveryBackups = new LinkedHashSet<>();
    for (int index = attemptedWrites - 1; index >= 0; index--) {
      StagedWrite stagedWrite = stagedWrites.get(index);
      try {
        if (stagedWrite.backup() == null) {
          Files.deleteIfExists(stagedWrite.target());
        } else {
          moveAtomically(stagedWrite.backup(), stagedWrite.target());
        }
      } catch (IOException | RuntimeException rollbackFailure) {
        rollbackComplete = false;
        if (stagedWrite.backup() != null) {
          recoveryBackups.add(stagedWrite.backup());
        }
        originalFailure.addSuppressed(rollbackFailure);
      }
    }
    cleanupStagedFiles(stagedWrites, originalFailure);
    cleanupBackupFiles(stagedWrites, recoveryBackups, originalFailure);
    cleanupCreatedDirectories(createdDirectories, originalFailure);
    if (!rollbackComplete) {
      LOG.error("Agent 写事务回滚不完整，保留的同目录恢复文件数: {}", recoveryBackups.size());
    }
  }

  private static void cleanupStagedFiles(
      List<StagedWrite> stagedWrites, Throwable originalFailure) {
    for (StagedWrite stagedWrite : stagedWrites) {
      try {
        Files.deleteIfExists(stagedWrite.staged());
      } catch (IOException | RuntimeException cleanupFailure) {
        originalFailure.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void cleanupBackupFiles(
      List<StagedWrite> stagedWrites, Set<Path> retained, Throwable originalFailure) {
    for (StagedWrite stagedWrite : stagedWrites) {
      Path backup = stagedWrite.backup();
      if (backup == null || retained.contains(backup)) {
        continue;
      }
      deleteArtifact(backup, originalFailure);
    }
  }

  private static void cleanupCreatedDirectories(
      Set<Path> createdDirectories, Throwable originalFailure) {
    List<Path> reversed =
        createdDirectories.stream()
            .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
            .toList();
    for (Path directory : reversed) {
      try {
        Files.deleteIfExists(directory);
      } catch (DirectoryNotEmptyException ignored) {
        // 外部进程在事务期间写入了该目录，不能删除它的数据。
      } catch (IOException | RuntimeException cleanupFailure) {
        originalFailure.addSuppressed(cleanupFailure);
      }
    }
  }

  private static void cleanupCommittedArtifactsBestEffort(List<StagedWrite> stagedWrites) {
    int failures = 0;
    for (StagedWrite stagedWrite : stagedWrites) {
      for (Path artifact : new Path[] {stagedWrite.staged(), stagedWrite.backup()}) {
        if (artifact == null) {
          continue;
        }
        try {
          Files.deleteIfExists(artifact);
        } catch (IOException | RuntimeException error) {
          failures++;
        }
      }
    }
    if (failures > 0) {
      LOG.warn("清理 Agent 写事务临时文件失败，残留数: {}", failures);
    }
  }

  private static void deleteArtifact(Path artifact, Throwable originalFailure) {
    if (artifact == null) {
      return;
    }
    try {
      Files.deleteIfExists(artifact);
    } catch (IOException | RuntimeException cleanupError) {
      originalFailure.addSuppressed(cleanupError);
    }
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private void ensureRealPathContained(Path parent) throws IOException {
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("目标父级不是普通目录: " + parent.getFileName());
    }
    Path agentsReal = agentsDir.toRealPath();
    Path parentReal = parent.toRealPath();
    if (!parentReal.startsWith(agentsReal)) {
      throw new IllegalArgumentException("目标父级越出 Agent 目录");
    }
  }

  /**
   * 从可信工作区根开始逐层建目录。每层都先以 NOFOLLOW 检查，再用单层 createDirectory， 因而不会像 createDirectories
   * 那样先穿过链接在根外制造目录、事后才发现越界。
   */
  private void createWorkspaceDirectoriesSafely(Path directory) throws IOException {
    Path target = directory.toAbsolutePath().normalize();
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("目标目录越出工作区");
    }
    ensureWorkspaceRoot();
    Path rootReal = oryxosRoot.toRealPath();
    Path current = oryxosRoot;
    for (Path segment : oryxosRoot.relativize(target)) {
      current = current.resolve(segment);
      createSingleDirectorySafely(current);
      Path currentReal = current.toRealPath();
      if (!currentReal.startsWith(rootReal)) {
        throw new IllegalArgumentException("目标目录越出工作区");
      }
    }
  }

  private void ensureWorkspaceRoot() throws IOException {
    Path parent = oryxosRoot.getParent();
    if (parent == null
        || Files.isSymbolicLink(parent)
        || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("工作区父目录不可用");
    }
    createSingleDirectorySafely(oryxosRoot);
  }

  private static void createSingleDirectorySafely(Path directory) throws IOException {
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      try {
        Files.createDirectory(directory);
      } catch (FileAlreadyExistsException ignored) {
        // 与进程外创建竞态时继续走下面的 NOFOLLOW 类型复检。
      }
    }
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("目录路径包含链接或非目录节点: " + directory.getFileName());
    }
  }

  /** 拒绝现有路径链中的链接；不存在的后代允许随后创建。 */
  private static void ensureNoSymbolicLinks(Path root, Path target) throws IOException {
    Path normalizedRoot = root.normalize();
    Path normalizedTarget = target.normalize();
    if (!normalizedTarget.startsWith(normalizedRoot)) {
      throw new IllegalArgumentException("文件路径越出 Agent 目录");
    }
    Path current = normalizedRoot;
    checkPathComponent(current, normalizedTarget.equals(normalizedRoot));
    Path relative = normalizedRoot.relativize(normalizedTarget);
    int index = 0;
    for (Path segment : relative) {
      current = current.resolve(segment);
      index++;
      checkPathComponent(current, index == relative.getNameCount());
    }
  }

  private static void checkPathComponent(Path path, boolean last) throws IOException {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    if (Files.isSymbolicLink(path)) {
      throw new IllegalArgumentException("文件路径包含符号链接: " + path.getFileName());
    }
    if (!last && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("文件路径父级不是目录: " + path.getFileName());
    }
  }

  @FunctionalInterface
  interface AtomicMover {

    void move(Path source, Path target) throws IOException;
  }

  private record StagedWrite(Path target, Path staged, Path backup) {}
}
