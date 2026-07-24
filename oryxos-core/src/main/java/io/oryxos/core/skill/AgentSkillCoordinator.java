package io.oryxos.core.skill;

import io.oryxos.core.profile.ProfileRegistry;
import java.nio.file.Path;
import java.util.Objects;

/** Opens long-lived request read leases and serializes all Agent Skill mutations. */
public final class AgentSkillCoordinator {

  private final SkillGraphCoordinator graphCoordinator;

  public AgentSkillCoordinator(
      Path agentsDir,
      ProfileRegistry profiles,
      AgentSkillCatalog catalog,
      AgentSkillLockRegistry locks) {
    Path root = Objects.requireNonNull(agentsDir, "agentsDir").toAbsolutePath().normalize();
    this.graphCoordinator =
        new SkillGraphCoordinator(
            root,
            Objects.requireNonNull(profiles, "profiles"),
            Objects.requireNonNull(catalog, "catalog"),
            Objects.requireNonNull(locks, "locks"));
  }

  /**
   * Acquires the read lock before rechecking Agent identity and building exactly one snapshot. The
   * returned lease keeps that lock until the complete ReAct request and Session save have finished.
   */
  public SkillLease openRequest(String agentName) {
    return graphCoordinator.openRequest(agentName);
  }

  /** Runs a managed filesystem mutation under the same fair per-Agent write lock. */
  public <T, E extends Exception> T mutate(
      String agentName, AgentSkillLockRegistry.CheckedSupplier<T, E> operation) throws E {
    return graphCoordinator.withAgentMutation(agentName, operation);
  }

  public SkillGraphCoordinator graph() {
    return graphCoordinator;
  }
}
