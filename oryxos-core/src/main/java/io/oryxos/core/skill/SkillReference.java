package io.oryxos.core.skill;

import java.nio.file.Path;

/** Active or archived Agent reference that blocks Skill archival. */
public record SkillReference(
    String agentName, AgentState state, String directoryName, Path linkPath) {
  public enum AgentState {
    ACTIVE,
    ARCHIVED
  }
}
