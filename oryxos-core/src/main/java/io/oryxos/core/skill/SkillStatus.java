package io.oryxos.core.skill;

/** A managed Skill's effective runtime state. */
public enum SkillStatus {
  ENABLED,
  DISABLED,
  INVALID;

  /** Validation failure always wins over the persisted administrator setting. */
  public static SkillStatus resolve(
      boolean configuredEnabled, SkillValidationError validationError) {
    if (validationError != null) {
      return INVALID;
    }
    return configuredEnabled ? ENABLED : DISABLED;
  }
}
