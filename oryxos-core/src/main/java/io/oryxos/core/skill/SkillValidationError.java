package io.oryxos.core.skill;

import java.util.Objects;

/** Stable validation code plus a safe, single-line human-readable message. */
public record SkillValidationError(SkillValidationCode code, String message) {

  private static final char CARRIAGE_RETURN = '\r';
  private static final char LINE_FEED = '\n';

  public SkillValidationError {
    code = Objects.requireNonNull(code, "code");
    if (message == null || message.isBlank()) {
      throw new IllegalArgumentException("validation message must not be blank");
    }
    message = message.strip();
    if (message.indexOf(CARRIAGE_RETURN) >= 0 || message.indexOf(LINE_FEED) >= 0) {
      throw new IllegalArgumentException("validation message must be a single line");
    }
  }
}
