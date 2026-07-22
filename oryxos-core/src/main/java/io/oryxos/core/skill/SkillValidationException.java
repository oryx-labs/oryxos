package io.oryxos.core.skill;

import java.io.Serial;
import java.util.Objects;

/** Strict Skill validation failure carrying only a stable code and a sanitized message. */
public final class SkillValidationException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  private final SkillValidationCode code;

  public SkillValidationException(SkillValidationError error) {
    super(Objects.requireNonNull(error, "error").message());
    this.code = error.code();
  }

  public SkillValidationException(SkillValidationCode code, String safeMessage) {
    this(new SkillValidationError(code, safeMessage));
  }

  public SkillValidationError error() {
    return new SkillValidationError(code, getMessage());
  }

  public SkillValidationCode code() {
    return code;
  }
}
