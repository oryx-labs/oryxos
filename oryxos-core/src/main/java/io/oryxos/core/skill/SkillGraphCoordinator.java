package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Fair global graph lock followed by stable per-Agent locks for every request and mutation. */
public final class SkillGraphCoordinator {

  private final Path agentsRoot;
  private final ProfileRegistry profiles;
  private final AgentSkillCatalog catalog;
  private final AgentSkillLockRegistry agentLocks;
  private final ReentrantReadWriteLock graph = new ReentrantReadWriteLock(true);

  public SkillGraphCoordinator(
      Path agentsRoot,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      AgentSkillLockRegistry agentLocks) {
    this.agentsRoot = Objects.requireNonNull(agentsRoot, "agentsRoot").toAbsolutePath().normalize();
    this.profiles = Objects.requireNonNull(profiles, "profiles");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.agentLocks = Objects.requireNonNull(agentLocks, "agentLocks");
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "UL_UNRELEASED_LOCK",
      justification =
          "The locks intentionally outlive this method and are released exactly once by the returned SkillLease; every pre-return exceptional path unlocks them.")
  public SkillLease openRequest(String agentName) {
    AgentName name = AgentName.parse(agentName);
    requireCurrentAgent(name);
    Lock graphRead = graph.readLock();
    Lock agentRead = agentLocks.readLock(name.value());
    graphRead.lock();
    boolean graphHeld = true;
    try {
      agentRead.lock();
      boolean agentHeld = true;
      try {
        requireCurrentAgent(name);
        SkillSnapshot snapshot = catalog.snapshot(name.value());
        return new SkillLease(
            snapshot,
            () -> {
              agentRead.unlock();
              graphRead.unlock();
            });
      } catch (RuntimeException error) {
        if (agentHeld) {
          agentRead.unlock();
        }
        throw error;
      }
    } catch (RuntimeException error) {
      if (graphHeld) {
        graphRead.unlock();
      }
      throw error;
    }
  }

  public <T, E extends Exception> T withGraphWrite(
      AgentSkillLockRegistry.CheckedSupplier<T, E> operation) throws E {
    Objects.requireNonNull(operation, "operation");
    Lock write = graph.writeLock();
    write.lock();
    try {
      return operation.get();
    } finally {
      write.unlock();
    }
  }

  public <T, E extends Exception> T withAgentMutation(
      String agentName, AgentSkillLockRegistry.CheckedSupplier<T, E> operation) throws E {
    String name = AgentName.parse(agentName).value();
    return withGraphWrite(() -> agentLocks.withWriteLock(name, operation));
  }

  public <T, E extends Exception> T withAgentsMutation(
      List<String> agentNames, AgentSkillLockRegistry.CheckedSupplier<T, E> operation) throws E {
    List<String> sorted =
        agentNames.stream()
            .map(name -> AgentName.parse(name).value())
            .distinct()
            .sorted(Comparator.comparing(name -> AgentName.parse(name).lockKey()))
            .toList();
    return withGraphWrite(() -> withAgentLocks(sorted, operation));
  }

  private <T, E extends Exception> T withAgentLocks(
      List<String> sorted, AgentSkillLockRegistry.CheckedSupplier<T, E> operation) throws E {
    List<Lock> acquired = new ArrayList<>(sorted.size());
    try {
      for (String agent : sorted) {
        Lock lock = agentLocks.writeLock(agent);
        lock.lock();
        acquired.add(lock);
      }
      return operation.get();
    } finally {
      for (int index = acquired.size() - 1; index >= 0; index--) {
        acquired.get(index).unlock();
      }
    }
  }

  private void requireCurrentAgent(AgentName name) {
    requireRealDirectory(agentsRoot, "Agent root is not a real directory");
    Path agentDir = agentsRoot.resolve(name.value()).normalize();
    if (!agentsRoot.equals(agentDir.getParent())
        || Files.isSymbolicLink(agentDir)
        || !Files.isDirectory(agentDir, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException("Agent directory does not exist: " + name.value());
    }
    name.requireFilesystemDirectoryName(agentDir);
    Profile profile =
        profiles
            .get(name.value())
            .orElseThrow(
                () -> new IllegalStateException("Agent profile does not exist: " + name.value()));
    name.requireProfileName(profile.name());
  }

  private static Path requireRealDirectory(Path directory, String message) {
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalStateException(message);
    }
    try {
      return directory.toRealPath();
    } catch (IOException error) {
      throw new IllegalStateException(message, error);
    }
  }
}
