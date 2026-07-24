package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import java.nio.file.Path;
import java.util.Objects;

/** Filesystem-derived relationship between one Agent and one public Skill. */
public record SkillAssociation(
    String agentName,
    String skillName,
    Path linkPath,
    String rawTarget,
    LinkStatus linkStatus,
    SkillStatus skillStatus,
    boolean discoverable,
    SkillValidationError error) {

  public SkillAssociation {
    agentName = AgentName.parse(agentName).value();
    skillName = SkillName.parse(skillName).value();
    linkPath = Objects.requireNonNull(linkPath, "linkPath").toAbsolutePath().normalize();
    rawTarget = rawTarget == null ? "" : rawTarget;
    linkStatus = Objects.requireNonNull(linkStatus, "linkStatus");
    boolean expected = linkStatus == LinkStatus.VALID && skillStatus == SkillStatus.ENABLED;
    if (discoverable != expected) {
      throw new IllegalArgumentException("discoverable is inconsistent with link and Skill state");
    }
    if (linkStatus == LinkStatus.INVALID && error == null) {
      throw new IllegalArgumentException("invalid association requires an error");
    }
  }

  public String relativeLinkPath() {
    return "agents/" + agentName + "/skills/" + skillName;
  }
}
