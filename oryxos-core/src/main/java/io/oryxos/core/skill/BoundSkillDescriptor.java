package io.oryxos.core.skill;

import java.nio.file.Path;

/** Level-1 Skill metadata exposed to an Agent; it deliberately has no body/resources field. */
public record BoundSkillDescriptor(String name, String description, Path linkPath, Path skillFile) {
  public String skillName() {
    return name;
  }
}
