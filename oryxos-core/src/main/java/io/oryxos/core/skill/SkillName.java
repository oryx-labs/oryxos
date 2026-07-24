package io.oryxos.core.skill;

import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Canonical public Skill identity and filesystem segment. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"IMPROPER_UNICODE", "CRLF_INJECTION_LOGS"},
    justification =
        "The strict ASCII grammar excludes controls; NFC plus Locale.ROOT folding is only a conservative filesystem collision key.")
public record SkillName(String value) implements Comparable<SkillName> {

  private static final Pattern GRAMMAR = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");

  public SkillName {
    if (value == null || !GRAMMAR.matcher(value).matches()) {
      throw new SkillValidationException(
          SkillValidationCode.INVALID_NAME,
          "Skill name must match [a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    }
  }

  public static SkillName parse(String value) {
    return new SkillName(value);
  }

  /** Case-folded NFC key used to reject identities that collide on common filesystems. */
  public String conflictKey() {
    return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
  }

  public void requireFilesystemDirectoryName(Path directory) {
    Objects.requireNonNull(directory, "directory");
    try {
      if (!FilesystemEntryNames.isStoredAs(directory, value)) {
        throw new SkillValidationException(
            SkillValidationCode.NAME_DIRECTORY_MISMATCH,
            "Skill directory identity does not match its manifest name");
      }
    } catch (IOException error) {
      throw new SkillValidationException(
          SkillValidationCode.CONTENT_UNREADABLE, "Skill directory identity cannot be inspected");
    }
  }

  @Override
  public int compareTo(SkillName other) {
    return value.compareTo(Objects.requireNonNull(other, "other").value);
  }

  @Override
  public String toString() {
    return value;
  }
}
