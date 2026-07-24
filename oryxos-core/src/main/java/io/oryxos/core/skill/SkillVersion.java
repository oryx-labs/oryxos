package io.oryxos.core.skill;

import java.util.regex.Pattern;

/** Optional manifest version constrained for safe future XML-attribute interpolation. */
public record SkillVersion(String value) {

  private static final Pattern GRAMMAR = Pattern.compile("[a-zA-Z0-9._+~-]{1,32}");

  public SkillVersion {
    if (value == null || !GRAMMAR.matcher(value).matches()) {
      throw new SkillValidationException(
          SkillValidationCode.INVALID_VERSION,
          "Skill version must contain only alphanumeric, dot, underscore, hyphen, plus or tilde");
    }
  }

  public static SkillVersion parse(String value) {
    return new SkillVersion(value);
  }

  public static SkillVersion optional(String value) {
    return value == null ? null : parse(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
