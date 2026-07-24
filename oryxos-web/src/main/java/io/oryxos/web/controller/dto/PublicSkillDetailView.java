package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.PublicSkillDescriptor;
import io.oryxos.core.skill.SkillValidationError;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Public Skill detail projection; package body and local absolute paths never cross the API. */
public record PublicSkillDetailView(
    String name,
    String description,
    String version,
    String status,
    boolean configuredEnabled,
    String source,
    Instant updatedAt,
    String entrypoint,
    List<String> linkedAgents,
    SkillValidationError validationError,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    List<String> resources,
    int fileCount,
    long totalBytes) {

  public static PublicSkillDetailView from(PublicSkillDescriptor skill) {
    var metadata = skill.metadata();
    return new PublicSkillDetailView(
        skill.name(),
        metadata == null ? null : metadata.description(),
        metadata == null || metadata.version() == null ? null : metadata.version().value(),
        skill.status().name().toLowerCase(java.util.Locale.ROOT),
        skill.configuredEnabled(),
        skill.source().name().toLowerCase(java.util.Locale.ROOT),
        skill.updatedAt(),
        skill.relativeEntrypoint(),
        skill.linkedAgents(),
        skill.validationError(),
        metadata == null ? null : metadata.license(),
        metadata == null ? null : metadata.compatibility(),
        metadata == null ? Map.of() : metadata.metadata(),
        metadata == null ? null : metadata.allowedTools(),
        skill.resources(),
        skill.fileCount(),
        skill.totalBytes());
  }
}
