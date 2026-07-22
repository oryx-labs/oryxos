package io.oryxos.web.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.oryxos.core.skill.SkillDescriptor;
import io.oryxos.core.skill.SkillMetadata;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Full management view for one Skill package; content and reserved files are deliberately absent.
 */
public record SkillDetailView(
    String name,
    String directoryName,
    String description,
    SkillSummaryView.Status status,
    boolean configuredEnabled,
    boolean catalogIncluded,
    SkillSummaryView.Source source,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt,
    SkillSummaryView.ValidationErrorView validationError,
    String entrypoint,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    List<String> resources,
    int fileCount,
    long totalBytes) {

  public SkillDetailView {
    metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    resources = resources == null ? List.of() : List.copyOf(resources);
  }

  public static SkillDetailView from(SkillDescriptor descriptor) {
    SkillMetadata metadata = descriptor.metadata();
    SkillSummaryView summary = SkillSummaryView.from(descriptor);
    return new SkillDetailView(
        summary.name(),
        summary.directoryName(),
        summary.description(),
        summary.status(),
        summary.configuredEnabled(),
        summary.catalogIncluded(),
        summary.source(),
        summary.updatedAt(),
        summary.validationError(),
        descriptor.relativeEntrypoint(),
        metadata == null ? null : metadata.license(),
        metadata == null ? null : metadata.compatibility(),
        metadata == null ? Map.of() : metadata.metadata(),
        metadata == null ? null : metadata.allowedTools(),
        descriptor.resources(),
        descriptor.fileCount(),
        descriptor.totalBytes());
  }
}
