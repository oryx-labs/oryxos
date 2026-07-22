package io.oryxos.core.skill;

import java.util.Objects;
import java.util.regex.Pattern;

/** Safe domain rejection for malformed or unsafe Skill imports. */
public sealed class SkillImportException extends RuntimeException
    permits SkillConflictException, SkillPackageTooLargeException {

  private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  private final String reasonCode;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "The class is sealed with only final permitted subclasses, and validation completes before constructor delegation.")
  public SkillImportException(String reasonCode, String safeMessage) {
    this(validate(reasonCode, safeMessage));
  }

  private SkillImportException(ValidatedFailure failure) {
    super(failure.safeMessage());
    this.reasonCode = failure.reasonCode();
  }

  public final String reasonCode() {
    return reasonCode;
  }

  private static String requireReasonCode(String value) {
    Objects.requireNonNull(value, "reasonCode");
    if (!REASON_CODE.matcher(value).matches()) {
      throw new IllegalArgumentException("reasonCode must be a stable upper-case identifier");
    }
    return value;
  }

  private static String requireSafeMessage(String value) {
    Objects.requireNonNull(value, "safeMessage");
    String message = value.strip();
    if (message.isEmpty()
        || message.codePoints().anyMatch(SkillImportException::isUnsafeMessageCodePoint)) {
      throw new IllegalArgumentException("safeMessage must be a non-blank single line");
    }
    return message;
  }

  private static ValidatedFailure validate(String reasonCode, String safeMessage) {
    String message = requireSafeMessage(safeMessage);
    return new ValidatedFailure(requireReasonCode(reasonCode), message);
  }

  private static boolean isUnsafeMessageCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }

  private record ValidatedFailure(String reasonCode, String safeMessage) {}
}
