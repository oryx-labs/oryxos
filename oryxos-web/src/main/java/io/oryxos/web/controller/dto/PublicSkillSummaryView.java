package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.PublicSkillDescriptor;
import io.oryxos.core.skill.SkillValidationError;
import java.time.Instant;
import java.util.List;

/** Public Skill list projection without absolute paths or prompt content. */
public record PublicSkillSummaryView(
    String name,
    String description,
    String status,
    boolean configuredEnabled,
    String source,
    Instant updatedAt,
    String entrypoint,
    List<String> linkedAgents,
    SkillValidationError validationError) {

  public static PublicSkillSummaryView from(PublicSkillDescriptor skill) {
    return new PublicSkillSummaryView(
        skill.name(),
        skill.metadata() == null ? null : skill.metadata().description(),
        skill.status().name().toLowerCase(java.util.Locale.ROOT),
        skill.configuredEnabled(),
        skill.source().name().toLowerCase(java.util.Locale.ROOT),
        skill.updatedAt(),
        skill.relativeEntrypoint(),
        skill.linkedAgents(),
        skill.validationError());
  }
}
