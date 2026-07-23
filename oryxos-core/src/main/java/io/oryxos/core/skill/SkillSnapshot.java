package io.oryxos.core.skill;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable request-level L1 catalog; it deliberately carries no Skill body or resource data. */
public record SkillSnapshot(
    String agentName,
    Instant capturedAt,
    List<SkillMetadata> skills,
    int renderedChars,
    int omittedCount) {

  public SkillSnapshot {
    if (agentName == null || agentName.isBlank()) {
      throw new IllegalArgumentException("agentName must not be blank");
    }
    capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    skills =
        skills == null
            ? List.of()
            : skills.stream().sorted(Comparator.comparing(SkillMetadata::name)).toList();
    Set<String> names = new HashSet<>();
    for (SkillMetadata skill : skills) {
      if (!names.add(skill.name())) {
        throw new IllegalArgumentException("duplicate Skill in snapshot: " + skill.name());
      }
    }
    if (renderedChars < 0) {
      throw new IllegalArgumentException("renderedChars must not be negative");
    }
    if (omittedCount < 0) {
      throw new IllegalArgumentException("omittedCount must not be negative");
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "The canonical constructor stores a sorted unmodifiable list of immutable Skill metadata values.")
  public List<SkillMetadata> skills() {
    return skills;
  }

  public static SkillSnapshot empty(String agentName) {
    return new SkillSnapshot(agentName, Instant.now(), List.of(), 0, 0);
  }
}
