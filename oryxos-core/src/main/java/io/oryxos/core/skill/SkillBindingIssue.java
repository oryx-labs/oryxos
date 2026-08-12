package io.oryxos.core.skill;

import java.nio.file.Path;

/** Agent Skill 绑定的一致性问题。无效项只报告并跳过，不自动修改用户文件。 */
public record SkillBindingIssue(
    String agentName,
    AgentState agentState,
    String entryName,
    Path path,
    Type type,
    String message) {

  public enum AgentState {
    ACTIVE,
    ARCHIVED,
    INVALID
  }

  public enum Type {
    DANGLING,
    ESCAPED,
    INVALID_TARGET,
    NAME_MISMATCH,
    STALE_REFERENCE
  }
}
