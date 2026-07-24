package io.oryxos.core.skill;

import java.util.Map;

/** Safe, bounded projection of one SKILL.md manifest. */
public record SkillManifest(
    SkillName name,
    String description,
    SkillVersion version,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    ActivationCriteria activation,
    GatingRequirements requires) {

  public SkillManifest {
    if (name == null) {
      throw new IllegalArgumentException("name is required");
    }
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("description is required");
    }
    description = description.strip();
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    activation = activation == null ? ActivationCriteria.empty() : activation;
    requires = requires == null ? GatingRequirements.empty() : requires;
  }
}
