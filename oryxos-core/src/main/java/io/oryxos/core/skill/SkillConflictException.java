package io.oryxos.core.skill;

/** Conflict raised when an active or unmanaged directory already owns the Skill name. */
public final class SkillConflictException extends SkillImportException {

  public static final String REASON_CODE = "SKILL_CONFLICT";

  public SkillConflictException() {
    this("A Skill with the same name already exists");
  }

  public SkillConflictException(String safeMessage) {
    super(REASON_CODE, safeMessage);
  }
}
