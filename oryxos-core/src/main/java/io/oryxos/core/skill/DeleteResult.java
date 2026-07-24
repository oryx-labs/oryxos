package io.oryxos.core.skill;

import java.util.List;

/** Result of archiving one public Skill package. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The canonical constructor replaces affectedAgents with an immutable list.")
public record DeleteResult(
    String skillName, boolean forced, List<String> affectedAgents, boolean archived) {
  public DeleteResult {
    skillName = SkillName.parse(skillName).value();
    affectedAgents =
        affectedAgents == null ? List.of() : affectedAgents.stream().distinct().sorted().toList();
  }
}
