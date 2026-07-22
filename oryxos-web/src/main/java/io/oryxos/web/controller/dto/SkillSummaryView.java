package io.oryxos.web.controller.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import io.oryxos.core.skill.SkillDescriptor;
import io.oryxos.core.skill.SkillMetadata;
import io.oryxos.core.skill.SkillSource;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.core.skill.SkillValidationError;
import java.time.Instant;

/** Compact management view returned by the Agent Skill collection endpoint. */
public record SkillSummaryView(
    String name,
    String directoryName,
    String description,
    Status status,
    boolean configuredEnabled,
    boolean catalogIncluded,
    Source source,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant updatedAt,
    ValidationErrorView validationError) {

  public static SkillSummaryView from(SkillDescriptor descriptor) {
    SkillMetadata metadata = descriptor.metadata();
    return new SkillSummaryView(
        metadata == null ? descriptor.directoryName() : metadata.name(),
        descriptor.directoryName(),
        metadata == null ? null : metadata.description(),
        Status.from(descriptor.status()),
        descriptor.configuredEnabled(),
        descriptor.catalogIncluded(),
        Source.from(descriptor.source()),
        descriptor.updatedAt(),
        ValidationErrorView.from(descriptor.validationError()));
  }

  /** Lower-case REST enum values match the published contract and remain visible in OpenAPI. */
  public enum Status {
    ENABLED("enabled"),
    DISABLED("disabled"),
    INVALID("invalid");

    private final String jsonValue;

    Status(String jsonValue) {
      this.jsonValue = jsonValue;
    }

    @JsonValue
    public String jsonValue() {
      return jsonValue;
    }

    private static Status from(SkillStatus status) {
      return switch (status) {
        case ENABLED -> ENABLED;
        case DISABLED -> DISABLED;
        case INVALID -> INVALID;
      };
    }
  }

  /** Skill package provenance exposed by the management API. */
  public enum Source {
    UPLOAD("upload"),
    WORKSPACE("workspace");

    private final String jsonValue;

    Source(String jsonValue) {
      this.jsonValue = jsonValue;
    }

    @JsonValue
    public String jsonValue() {
      return jsonValue;
    }

    private static Source from(SkillSource source) {
      return switch (source) {
        case UPLOAD -> UPLOAD;
        case WORKSPACE -> WORKSPACE;
      };
    }
  }

  /** Stable validation code plus a safe display message; never contains filesystem paths. */
  public record ValidationErrorView(String code, String message) {

    private static ValidationErrorView from(SkillValidationError error) {
      return error == null ? null : new ValidationErrorView(error.code().name(), error.message());
    }
  }
}
