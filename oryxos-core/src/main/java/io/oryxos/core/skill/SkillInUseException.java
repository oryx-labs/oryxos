package io.oryxos.core.skill;

import java.io.Serial;
import java.util.List;

/** Typed normal-delete conflict carrying the fresh, complete Agent scan. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "linkedAgents is created by Stream.toList and is immutable.")
public final class SkillInUseException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;
  public static final String REASON_CODE = "SKILL_IN_USE";

  private final String skillName;
  private final List<String> linkedAgents;

  public SkillInUseException(String skillName, List<String> linkedAgents) {
    super("Skill is still associated with Agents");
    this.skillName = SkillName.parse(skillName).value();
    this.linkedAgents = linkedAgents.stream().distinct().sorted().toList();
  }

  public String skillName() {
    return skillName;
  }

  public List<String> linkedAgents() {
    return linkedAgents;
  }

  public String reasonCode() {
    return REASON_CODE;
  }
}
