package io.oryxos.core.skill;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Safe management view derived from one direct public Skill package. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "The canonical constructor replaces resources and linkedAgents with immutable lists.")
public record PublicSkillDescriptor(
    String name,
    SkillMetadata metadata,
    SkillStatus status,
    boolean configuredEnabled,
    SkillSource source,
    Instant updatedAt,
    SkillValidationError validationError,
    String relativeEntrypoint,
    List<String> resources,
    int fileCount,
    long totalBytes,
    List<String> linkedAgents) {

  public PublicSkillDescriptor {
    name = SkillName.parse(name).value();
    status = Objects.requireNonNull(status, "status");
    source = Objects.requireNonNull(source, "source");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (status != SkillStatus.resolve(configuredEnabled, validationError)) {
      throw new IllegalArgumentException("status is inconsistent with validation");
    }
    if (status != SkillStatus.INVALID && metadata == null) {
      throw new IllegalArgumentException("valid public Skill requires metadata");
    }
    resources = resources == null ? List.of() : List.copyOf(resources);
    linkedAgents =
        linkedAgents == null ? List.of() : linkedAgents.stream().distinct().sorted().toList();
    if (fileCount < 0 || totalBytes < 0) {
      throw new IllegalArgumentException("content statistics must not be negative");
    }
  }

  public boolean discoverable() {
    return status == SkillStatus.ENABLED;
  }

  public PublicSkillDescriptor withLinkedAgents(List<String> agents) {
    return new PublicSkillDescriptor(
        name,
        metadata,
        status,
        configuredEnabled,
        source,
        updatedAt,
        validationError,
        relativeEntrypoint,
        resources,
        fileCount,
        totalBytes,
        agents);
  }

  /** Avoids accidentally logging the internal absolute entry path carried by SkillMetadata. */
  @Override
  public String toString() {
    return "PublicSkillDescriptor[name="
        + name
        + ", status="
        + status
        + ", configuredEnabled="
        + configuredEnabled
        + ", source="
        + source
        + ", relativeEntrypoint="
        + relativeEntrypoint
        + "]";
  }
}
