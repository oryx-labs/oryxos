package io.oryxos.core.skill;

import java.util.List;
import java.util.Objects;

/** Locking and domain-event facade over the low-level association filesystem operations. */
public final class SkillAssociationManager {

  private final SkillAssociationService associations;
  private final SkillGraphCoordinator graph;
  private final SkillManagementEventLogger events;

  public SkillAssociationManager(
      SkillAssociationService associations,
      SkillGraphCoordinator graph,
      SkillManagementEventLogger events) {
    this.associations = Objects.requireNonNull(associations, "associations");
    this.graph = Objects.requireNonNull(graph, "graph");
    this.events = Objects.requireNonNull(events, "events");
  }

  public List<SkillAssociation> list(String agentName) {
    return associations.list(agentName);
  }

  public SkillAssociation associate(String agentName, String skillName) {
    try {
      SkillAssociation result =
          graph.withAgentMutation(agentName, () -> associations.associate(agentName, skillName));
      events.record(
          "associate", "success", result.skillName(), result.agentName(), List.of(), null);
      return result;
    } catch (SkillValidationException | SkillConflictException error) {
      events.record("associate", "rejected", skillName, agentName, List.of(), reason(error));
      throw error;
    } catch (RuntimeException error) {
      events.record("associate", "failed", skillName, agentName, List.of(), "IO_FAILURE");
      throw error;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public SkillAssociation unlink(String agentName, String skillName) {
    try {
      SkillAssociation result =
          graph.withAgentMutation(agentName, () -> associations.unlink(agentName, skillName));
      events.record("unlink", "success", result.skillName(), result.agentName(), List.of(), null);
      return result;
    } catch (SkillConflictException error) {
      events.record("unlink", "rejected", skillName, agentName, List.of(), reason(error));
      throw error;
    } catch (RuntimeException error) {
      events.record("unlink", "failed", skillName, agentName, List.of(), "IO_FAILURE");
      throw error;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static String reason(Throwable error) {
    if (error instanceof SkillValidationException validation) {
      return validation.code().name();
    }
    if (error instanceof SkillImportException imported) {
      return imported.reasonCode();
    }
    return error.getClass().getSimpleName();
  }
}
