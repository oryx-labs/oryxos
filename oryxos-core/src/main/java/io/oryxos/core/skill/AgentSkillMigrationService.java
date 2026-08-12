package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentMarkdown;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One-time, per-Agent atomic migration from legacy frontmatter skills to local symlink bindings.
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "Binding service is an injected workspace coordinator, intentionally shared by reference.")
public final class AgentSkillMigrationService {

  private final Path agentsDir;
  private final AgentSkillBindingService bindings;

  public AgentSkillMigrationService(Path oryxosRoot, AgentSkillBindingService bindings) {
    this.agentsDir = oryxosRoot.toAbsolutePath().normalize().resolve("agents");
    this.bindings = bindings;
  }

  public AgentSkillStartupReport migrateAll() {
    if (!Files.isDirectory(agentsDir, LinkOption.NOFOLLOW_LINKS)) {
      return new AgentSkillStartupReport(List.of(), bindings.reconcile());
    }
    List<MigrationResult> results = new ArrayList<>();
    try (Stream<Path> dirs = Files.list(agentsDir)) {
      dirs.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
          .sorted()
          .forEach(path -> results.add(migrate(path)));
    } catch (IOException e) {
      throw new UncheckedIOException("扫描旧 Agent Skill 配置失败", e);
    }
    return new AgentSkillStartupReport(results, bindings.reconcile());
  }

  public MigrationResult migrate(Path agentDirectory) {
    return bindings.coordinate(() -> migrateLocked(agentDirectory));
  }

  private MigrationResult migrateLocked(Path agentDirectory) {
    String agent = String.valueOf(agentDirectory.getFileName());
    Path markdown = agentDirectory.resolve("AGENT.md");
    if (!Files.isRegularFile(markdown)) {
      return new MigrationResult(agent, Status.NOT_NEEDED, "缺少 AGENT.md");
    }
    byte[] original;
    String text;
    try {
      original = Files.readAllBytes(markdown);
      text = new String(original, java.nio.charset.StandardCharsets.UTF_8);
    } catch (IOException e) {
      return new MigrationResult(agent, Status.FAILED, "读取 AGENT.md 失败");
    }
    if (!AgentMarkdown.hasLegacySkills(text)) {
      return new MigrationResult(agent, Status.NOT_NEEDED, "无需迁移");
    }
    List<String> legacy;
    try {
      legacy = AgentMarkdown.legacySkills(text);
    } catch (RuntimeException e) {
      return new MigrationResult(agent, Status.FAILED, sanitize(e.getMessage()));
    }
    List<String> before =
        bindings.inspect(agent).bindings().stream().map(BoundSkillDescriptor::name).toList();
    Set<String> desired = new LinkedHashSet<>(before);
    desired.addAll(legacy);
    Path temporary = markdown.resolveSibling(".AGENT.md.migrate-" + UUID.randomUUID());
    try {
      String migrated = AgentMarkdown.removeLegacySkills(text);
      if (AgentMarkdown.hasLegacySkills(migrated)) {
        throw new IllegalArgumentException("旧版顶层 skills 未能安全移除");
      }
      Files.writeString(temporary, migrated);
      bindings.replaceBindings(agent, List.copyOf(desired));
      moveAtomic(temporary, markdown);
      return new MigrationResult(agent, Status.MIGRATED, "已迁移 " + legacy.size() + " 个 Skill");
    } catch (IOException | RuntimeException e) {
      try {
        bindings.replaceBindings(agent, before);
      } catch (RuntimeException ignored) {
        // Reconciliation exposes rollback residue while the original error remains primary.
      }
      try {
        Files.deleteIfExists(temporary);
        if (!java.util.Arrays.equals(original, Files.readAllBytes(markdown))) {
          Files.write(markdown, original);
        }
      } catch (IOException ignored) {
        // The failure is reported; startup continues with other Agents.
      }
      return new MigrationResult(agent, Status.FAILED, sanitize(e.getMessage()));
    }
  }

  private static void moveAtomic(Path source, Path target) throws IOException {
    try {
      Files.move(
          source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      throw new IOException("文件系统不支持 AGENT.md 原子迁移", e);
    }
  }

  private static String sanitize(String value) {
    return value == null ? "迁移失败" : value.replace('\r', '_').replace('\n', '_');
  }

  public enum Status {
    NOT_NEEDED,
    MIGRATED,
    FAILED
  }

  public record MigrationResult(String agentName, Status status, String message) {}
}
