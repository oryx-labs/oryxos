package io.oryxos.core.skill;

/** Resource-budget rejection mapped to HTTP 413 by the Web boundary. */
public final class SkillPackageTooLargeException extends SkillImportException {

  public SkillPackageTooLargeException(String reasonCode, String safeMessage) {
    super(reasonCode, safeMessage);
  }
}
