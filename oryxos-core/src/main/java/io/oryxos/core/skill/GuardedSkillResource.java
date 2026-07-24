package io.oryxos.core.skill;

import java.nio.file.Path;
import java.util.Objects;

/** Canonical resource accepted by the Skill access guard before normal Tool sandboxing. */
public record GuardedSkillResource(String skillName, Path path) {
  public GuardedSkillResource {
    skillName = SkillName.parse(skillName).value();
    path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
  }
}
