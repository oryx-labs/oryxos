package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.locks.Lock;

/** Opens long-lived request read leases and serializes all Agent Skill mutations. */
public final class AgentSkillCoordinator {

  private final Path agentsDir;
  private final ProfileRegistry profiles;
  private final AgentSkillCatalog catalog;
  private final AgentSkillLockRegistry locks;

  public AgentSkillCoordinator(
      Path agentsDir,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      AgentSkillLockRegistry locks) {
    this.agentsDir = Objects.requireNonNull(agentsDir, "agentsDir").toAbsolutePath().normalize();
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.locks = Objects.requireNonNull(locks, "locks");
  }

  /**
   * Acquires the read lock before rechecking Agent identity and building exactly one snapshot. The
   * returned lease keeps that lock until the complete ReAct request and Session save have finished.
   */
  public SkillLease openRequest(String agentName) {
    AgentName name = AgentName.parse(agentName);
    // Reject unknown/stale Agent names before allocating their permanent lock-map entry. The
    // second check under the lock closes the mutation race between this fast preflight and the
    // snapshot build.
    requireCurrentAgent(name);
    Lock readLock = locks.readLock(name.value());
    readLock.lock();
    boolean leased = false;
    try {
      requireCurrentAgent(name);
      SkillLease lease = new SkillLease(catalog.snapshot(name.value()), readLock::unlock);
      leased = true;
      return lease;
    } finally {
      if (!leased) {
        readLock.unlock();
      }
    }
  }

  /** Runs a managed filesystem mutation under the same fair per-Agent write lock. */
  public <T, E extends Exception> T mutate(
      String agentName, AgentSkillLockRegistry.CheckedSupplier<T, E> operation) throws E {
    return locks.withWriteLock(agentName, operation);
  }

  private void requireCurrentAgent(AgentName name) {
    Path agentsReal = requireRealDirectory(agentsDir, "Agent root is not a real directory");
    Path agentDir = agentsDir.resolve(name.value()).normalize();
    if (!agentsDir.equals(agentDir.getParent())
        || Files.isSymbolicLink(agentDir)
        || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Agent directory does not exist: " + name.value());
    }
    Path agentReal =
        requireRealDirectory(agentDir, "Agent directory cannot be inspected: " + name.value());
    if (!agentsReal.equals(agentReal.getParent())) {
      throw new IllegalStateException("Agent directory is outside the Agent root: " + name.value());
    }
    try {
      name.requireFilesystemDirectoryName(agentDir);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "Agent directory identity does not match: " + name.value(), error);
    }
    Profile profile =
        profiles
            .get(name.value())
            .orElseThrow(
                () -> new IllegalStateException("Agent profile does not exist: " + name.value()));
    name.requireProfileName(profile.name());
  }

  private static Path requireRealDirectory(Path directory, String safeMessage) {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(safeMessage);
    }
    try {
      Path real = directory.toRealPath();
      if (Files.isSymbolicLink(directory)
          || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException(safeMessage);
      }
      return real;
    } catch (java.io.IOException error) {
      throw new IllegalStateException(safeMessage, error);
    }
  }
}
