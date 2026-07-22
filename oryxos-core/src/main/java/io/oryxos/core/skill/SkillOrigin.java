package io.oryxos.core.skill;

import java.time.Instant;
import java.util.Objects;

/** Trusted contents persisted by OryxOS in {@code .oryxos-origin.yml}. */
public record SkillOrigin(
    int schemaVersion, SkillSource sourceType, String originalFilename, Instant importedAt) {

  public static final int CURRENT_SCHEMA_VERSION = 1;

  private static final int MAX_FILENAME_CHARS = 255;
  private static final String CURRENT_DIRECTORY = ".";
  private static final String PARENT_DIRECTORY = "..";

  public SkillOrigin {
    if (schemaVersion != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("unsupported Skill origin schema: " + schemaVersion);
    }
    sourceType = Objects.requireNonNull(sourceType, "sourceType");
    if (sourceType != SkillSource.UPLOAD) {
      throw new IllegalArgumentException("origin sidecar is only valid for uploaded Skills");
    }
    if (originalFilename == null || originalFilename.isBlank()) {
      throw new IllegalArgumentException("originalFilename must not be blank");
    }
    originalFilename = originalFilename.strip();
    if (originalFilename.codePointCount(0, originalFilename.length()) > MAX_FILENAME_CHARS
        || CURRENT_DIRECTORY.equals(originalFilename)
        || PARENT_DIRECTORY.equals(originalFilename)
        || originalFilename.indexOf('/') >= 0
        || originalFilename.indexOf('\\') >= 0
        || originalFilename.codePoints().anyMatch(SkillOrigin::isUnsafeFilenameCodePoint)) {
      throw new IllegalArgumentException("originalFilename must be a sanitized basename");
    }
    importedAt = Objects.requireNonNull(importedAt, "importedAt");
  }

  private static boolean isUnsafeFilenameCodePoint(int codePoint) {
    int type = Character.getType(codePoint);
    return Character.isISOControl(codePoint)
        || type == Character.FORMAT
        || type == Character.LINE_SEPARATOR
        || type == Character.PARAGRAPH_SEPARATOR;
  }
}
