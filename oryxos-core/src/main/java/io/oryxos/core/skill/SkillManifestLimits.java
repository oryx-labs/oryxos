package io.oryxos.core.skill;

/** Fixed compatibility limits for optional Skill manifest hints. */
public final class SkillManifestLimits {

  public static final int MAX_KEYWORDS = 20;
  public static final int MAX_EXCLUDE_KEYWORDS = 20;
  public static final int MAX_PATTERNS = 5;
  public static final int MAX_TAGS = 10;
  public static final int MIN_KEYWORD_OR_TAG_LENGTH = 3;
  public static final int MAX_SETUP_MARKER_BYTES = 256;
  public static final int MAX_REQUIRED_SKILLS = 10;

  private SkillManifestLimits() {}
}
