package io.oryxos.core.skill;

import java.util.List;

/** Raised when an installed Skill cannot be archived because Agent links still reference it. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SE_BAD_FIELD",
    justification =
        "This in-process HTTP mapping exception is never Java-serialized; references are immutable response data.")
public final class SkillReferencedException extends IllegalStateException {

  private final String skillName;
  private final List<SkillReference> references;

  public SkillReferencedException(String skillName, List<SkillReference> references) {
    super("Skill 仍被 Agent 引用: " + skillName);
    this.skillName = skillName;
    this.references = List.copyOf(references);
  }

  public String skillName() {
    return skillName;
  }

  public List<SkillReference> references() {
    return references;
  }
}
