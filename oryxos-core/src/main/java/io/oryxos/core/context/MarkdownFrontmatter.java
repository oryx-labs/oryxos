package io.oryxos.core.context;

import io.oryxos.core.skill.SkillValidationCode;
import io.oryxos.core.skill.SkillValidationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.Set;

/** Bounded, strict frontmatter scanner shared by untrusted Skills and legacy Agent splitting. */
public final class MarkdownFrontmatter {

  private static final String FENCE = "---";
  private static final char BOM = '\ufeff';
  private static final char CARRIAGE_RETURN = '\r';
  private static final char LINE_FEED = '\n';
  private static final int MAX_FENCE_LINE_BYTES = 1024;
  private static final int ASCII_LIMIT = 0x80;
  private static final int UTF8_TWO_BYTE_MIN = 0xc2;
  private static final int UTF8_TWO_BYTE_MAX = 0xdf;
  private static final int UTF8_THREE_BYTE_MIN = 0xe0;
  private static final int UTF8_THREE_BYTE_MAX = 0xef;
  private static final int UTF8_FOUR_BYTE_MIN = 0xf0;
  private static final int UTF8_FOUR_BYTE_MAX = 0xf4;

  private MarkdownFrontmatter() {}

  /** Parsed Skill header. It intentionally carries no prompt body or resource content. */
  public record Parsed(String yaml, int bodyStart, boolean hasNonBlankPrompt) {
    public Parsed {
      yaml = Objects.requireNonNull(yaml, "yaml");
      if (bodyStart < 0) {
        throw new IllegalArgumentException("bodyStart must not be negative");
      }
    }
  }

  /** Tolerant in-memory split used only by legacy {@code AGENT.md}. */
  public record Split(boolean hasFrontmatter, String yaml, String body) {
    public Split {
      yaml = yaml == null ? "" : yaml;
      body = body == null ? "" : body;
    }
  }

  /** Compatibility overload when one bound is intentionally used for both file and header. */
  public static Parsed read(Path file, long maxBytes) {
    return read(file, maxBytes, maxBytes);
  }

  /**
   * Reads only the frontmatter and enough body code points to prove the prompt is non-empty.
   * Symlinks are never followed and every reported message uses only the safe basename.
   */
  public static Parsed read(Path file, long maxSkillBytes, long maxFrontmatterBytes) {
    requirePositive(maxSkillBytes, "maxSkillBytes");
    requirePositive(maxFrontmatterBytes, "maxFrontmatterBytes");
    Objects.requireNonNull(file, "file");
    String safeName = safeFileName(file);
    BasicFileAttributes attributes;
    try {
      attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      throw failure(SkillValidationCode.MISSING_ENTRYPOINT, safeName + " is missing");
    } catch (IOException e) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, safeName + " cannot be inspected");
    }
    if (attributes.isSymbolicLink()) {
      throw failure(SkillValidationCode.LINK_NOT_ALLOWED, safeName + " must not be a link");
    }
    if (!attributes.isRegularFile()) {
      throw failure(
          SkillValidationCode.SPECIAL_FILE_NOT_ALLOWED, safeName + " must be a regular file");
    }
    if (attributes.size() > maxSkillBytes) {
      throw failure(
          SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, safeName + " exceeds the size limit");
    }

    Set<OpenOption> options = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    try (SeekableByteChannel channel = Files.newByteChannel(file, options)) {
      return read(
          Channels.newInputStream(channel),
          safeName,
          channel.size(),
          maxSkillBytes,
          maxFrontmatterBytes);
    } catch (SkillValidationException e) {
      throw e;
    } catch (IOException e) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, safeName + " cannot be read");
    }
  }

  /** Package-private stream seam used to prove bounded reads and close behavior. */
  static Parsed read(
      InputStream input,
      String safeFileName,
      long knownSize,
      long maxSkillBytes,
      long maxFrontmatterBytes) {
    requirePositive(maxSkillBytes, "maxSkillBytes");
    requirePositive(maxFrontmatterBytes, "maxFrontmatterBytes");
    String safeName = sanitizeFileName(safeFileName);
    if (knownSize > maxSkillBytes) {
      closeQuietly(input);
      throw failure(
          SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, safeName + " exceeds the size limit");
    }

    try (InputStream closeable = input) {
      ByteCursor cursor = new ByteCursor(closeable, maxSkillBytes, safeName);
      return scan(cursor, safeName, maxFrontmatterBytes);
    } catch (SkillValidationException e) {
      throw e;
    } catch (IOException e) {
      throw failure(SkillValidationCode.CONTENT_UNREADABLE, safeName + " cannot be read");
    }
  }

  private static Parsed scan(ByteCursor cursor, String safeName, long maxFrontmatterBytes)
      throws IOException {
    int normalizedPosition = 0;
    boolean firstPhysicalLine = true;
    RawLine opening;
    String openingText;
    while (true) {
      opening = readLine(cursor, MAX_FENCE_LINE_BYTES, safeName, false);
      if (opening == null) {
        throw failure(
            SkillValidationCode.MISSING_FRONTMATTER, safeName + " is missing frontmatter");
      }
      openingText = decode(opening.bytes(), safeName);
      if (firstPhysicalLine) {
        openingText = stripBom(openingText);
        firstPhysicalLine = false;
      }
      if (openingText.isEmpty() && opening.terminated()) {
        normalizedPosition++;
        continue;
      }
      break;
    }
    if (!opening.terminated() || !FENCE.equals(openingText.stripTrailing())) {
      throw failure(SkillValidationCode.MISSING_FRONTMATTER, safeName + " is missing frontmatter");
    }
    normalizedPosition += openingText.length() + 1;

    long yamlStartBytes = cursor.position();
    StringBuilder yaml = new StringBuilder();
    boolean firstYamlLine = true;
    while (true) {
      long usedBytes = cursor.position() - yamlStartBytes;
      long remainingBytes = Math.max(0, maxFrontmatterBytes - usedBytes);
      long boundedLineBytes = Math.min(Integer.MAX_VALUE, remainingBytes + MAX_FENCE_LINE_BYTES);
      RawLine line = readLine(cursor, boundedLineBytes, safeName, true);
      if (line == null) {
        throw failure(
            SkillValidationCode.UNCLOSED_FRONTMATTER, safeName + " frontmatter is not closed");
      }
      String text = decode(line.bytes(), safeName);
      if (FENCE.equals(text.strip())) {
        if (line.startPosition() - yamlStartBytes > maxFrontmatterBytes) {
          throw failure(
              SkillValidationCode.FRONTMATTER_TOO_LARGE,
              safeName + " frontmatter exceeds the size limit");
        }
        normalizedPosition += text.length() + (line.terminated() ? 1 : 0);
        BodyProbe body = probeBody(cursor, normalizedPosition, safeName);
        if (!body.hasNonBlankPrompt()) {
          throw failure(SkillValidationCode.EMPTY_PROMPT, safeName + " prompt is empty");
        }
        return new Parsed(yaml.toString(), body.bodyStart(), true);
      }

      long frontmatterBytes = cursor.position() - yamlStartBytes;
      if (frontmatterBytes > maxFrontmatterBytes) {
        throw failure(
            SkillValidationCode.FRONTMATTER_TOO_LARGE,
            safeName + " frontmatter exceeds the size limit");
      }
      if (!firstYamlLine) {
        yaml.append('\n');
      }
      yaml.append(text);
      firstYamlLine = false;
      normalizedPosition += text.length() + (line.terminated() ? 1 : 0);
    }
  }

  private static BodyProbe probeBody(ByteCursor cursor, int normalizedPosition, String safeName)
      throws IOException {
    NormalizedCodePointReader reader = new NormalizedCodePointReader(cursor, safeName);
    int bodyStart = normalizedPosition;
    boolean onlyLeadingNewlines = true;
    while (true) {
      int codePoint = reader.read();
      if (codePoint < 0) {
        return new BodyProbe(bodyStart, false);
      }
      if (onlyLeadingNewlines && codePoint == LINE_FEED) {
        bodyStart++;
      } else {
        onlyLeadingNewlines = false;
      }
      if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
        return new BodyProbe(bodyStart, true);
      }
    }
  }

  /**
   * Legacy Agent splitting uses the same normalization and fence recognition, but malformed input
   * deliberately falls back to the original whole body instead of raising Skill errors.
   */
  public static Split split(String content) {
    if (content == null || content.isEmpty()) {
      return new Split(false, "", "");
    }
    String normalized = content.replace("\r\n", "\n").replace(CARRIAGE_RETURN, LINE_FEED);
    if (!normalized.isEmpty() && normalized.charAt(0) == BOM) {
      normalized = normalized.substring(1);
    }
    int openingStart = 0;
    while (openingStart < normalized.length() && normalized.charAt(openingStart) == LINE_FEED) {
      openingStart++;
    }
    int openingEnd = normalized.indexOf(LINE_FEED, openingStart);
    if (openingEnd < 0 || !FENCE.equals(normalized.substring(openingStart, openingEnd).strip())) {
      return new Split(false, "", content.strip());
    }

    int yamlStart = openingEnd + 1;
    int lineStart = yamlStart;
    while (lineStart <= normalized.length()) {
      int lineEnd = normalized.indexOf(LINE_FEED, lineStart);
      int contentEnd = lineEnd < 0 ? normalized.length() : lineEnd;
      String line = normalized.substring(lineStart, contentEnd);
      if (FENCE.equals(line.strip())) {
        String yaml = normalized.substring(yamlStart, lineStart);
        if (yaml.endsWith("\n")) {
          yaml = yaml.substring(0, yaml.length() - 1);
        }
        int bodyStart = lineEnd < 0 ? normalized.length() : lineEnd + 1;
        return new Split(true, yaml, normalized.substring(bodyStart).strip());
      }
      if (lineEnd < 0) {
        break;
      }
      lineStart = lineEnd + 1;
    }
    return new Split(false, "", content.strip());
  }

  private static RawLine readLine(
      ByteCursor cursor, long maxLineBytes, String safeName, boolean frontmatter)
      throws IOException {
    long start = cursor.position();
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    boolean terminated = false;
    while (true) {
      int value = cursor.read();
      if (value < 0) {
        break;
      }
      if (value == '\n') {
        terminated = true;
        break;
      }
      if (value == '\r') {
        int next = cursor.read();
        if (next >= 0 && next != '\n') {
          cursor.pushBack(next);
        }
        terminated = true;
        break;
      }
      bytes.write(value);
      if (bytes.size() > maxLineBytes) {
        SkillValidationCode code =
            frontmatter
                ? SkillValidationCode.FRONTMATTER_TOO_LARGE
                : SkillValidationCode.MISSING_FRONTMATTER;
        throw failure(code, safeName + " frontmatter exceeds the size limit");
      }
    }
    if (cursor.position() == start && bytes.size() == 0 && !terminated) {
      return null;
    }
    return new RawLine(bytes.toByteArray(), terminated, start);
  }

  private static String decode(byte[] bytes, String safeName) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException e) {
      throw failure(SkillValidationCode.INVALID_UTF8, safeName + " is not valid UTF-8");
    }
  }

  private static String stripBom(String value) {
    return !value.isEmpty() && value.charAt(0) == BOM ? value.substring(1) : value;
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static String safeFileName(Path file) {
    Path basename = Objects.requireNonNull(file, "file").getFileName();
    if (basename == null) {
      return "SKILL.md";
    }
    return sanitizeFileName(basename.toString());
  }

  private static String sanitizeFileName(String value) {
    if (value == null || value.isBlank()) {
      return "SKILL.md";
    }
    StringBuilder safe = new StringBuilder(Math.min(value.length(), 128));
    value
        .codePoints()
        .limit(128)
        .forEach(
            codePoint -> {
              int type = Character.getType(codePoint);
              safe.appendCodePoint(
                  codePoint == '/'
                          || codePoint == '\\'
                          || Character.isISOControl(codePoint)
                          || type == Character.FORMAT
                          || type == Character.LINE_SEPARATOR
                          || type == Character.PARAGRAPH_SEPARATOR
                      ? '_'
                      : codePoint);
            });
    return safe.toString();
  }

  private static SkillValidationException failure(SkillValidationCode code, String message) {
    return new SkillValidationException(code, message);
  }

  private static void closeQuietly(InputStream input) {
    if (input == null) {
      return;
    }
    try {
      input.close();
    } catch (IOException ignored) {
      // The validation result must remain stable; a close failure must not expose a raw exception.
    }
  }

  private record RawLine(byte[] bytes, boolean terminated, long startPosition) {}

  private record BodyProbe(int bodyStart, boolean hasNonBlankPrompt) {}

  private static final class ByteCursor {

    private final InputStream input;
    private final long maxBytes;
    private final String safeName;
    private long position;
    private int pushedBack = -1;

    private ByteCursor(InputStream input, long maxBytes, String safeName) {
      this.input = Objects.requireNonNull(input, "input");
      this.maxBytes = maxBytes;
      this.safeName = safeName;
    }

    private int read() throws IOException {
      int value;
      if (pushedBack >= 0) {
        value = pushedBack;
        pushedBack = -1;
      } else {
        value = input.read();
      }
      if (value >= 0) {
        position++;
        if (position > maxBytes) {
          throw failure(
              SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, safeName + " exceeds the size limit");
        }
      }
      return value;
    }

    private void pushBack(int value) {
      if (value < 0 || pushedBack >= 0) {
        throw new IllegalStateException("invalid byte pushback");
      }
      pushedBack = value;
      position--;
    }

    private long position() {
      return position;
    }
  }

  private static final class NormalizedCodePointReader {

    private final ByteCursor cursor;
    private final String safeName;
    private int pushedBack = -1;

    private NormalizedCodePointReader(ByteCursor cursor, String safeName) {
      this.cursor = cursor;
      this.safeName = safeName;
    }

    private int read() throws IOException {
      int codePoint = readRaw();
      if (codePoint != CARRIAGE_RETURN) {
        return codePoint;
      }
      int next = readRaw();
      if (next >= 0 && next != LINE_FEED) {
        pushedBack = next;
      }
      return LINE_FEED;
    }

    private int readRaw() throws IOException {
      if (pushedBack >= 0) {
        int value = pushedBack;
        pushedBack = -1;
        return value;
      }
      int first = cursor.read();
      if (first < 0 || first < ASCII_LIMIT) {
        return first;
      }
      int length;
      int codePoint;
      int minimum;
      if (first >= UTF8_TWO_BYTE_MIN && first <= UTF8_TWO_BYTE_MAX) {
        length = 2;
        codePoint = first & 0x1f;
        minimum = 0x80;
      } else if (first >= UTF8_THREE_BYTE_MIN && first <= UTF8_THREE_BYTE_MAX) {
        length = 3;
        codePoint = first & 0x0f;
        minimum = 0x800;
      } else if (first >= UTF8_FOUR_BYTE_MIN && first <= UTF8_FOUR_BYTE_MAX) {
        length = 4;
        codePoint = first & 0x07;
        minimum = 0x10000;
      } else {
        throw invalidUtf8();
      }
      for (int i = 1; i < length; i++) {
        int continuation = cursor.read();
        if (continuation < 0 || (continuation & 0xc0) != 0x80) {
          throw invalidUtf8();
        }
        codePoint = (codePoint << 6) | (continuation & 0x3f);
      }
      if (codePoint < minimum || codePoint > Character.MAX_CODE_POINT) {
        throw invalidUtf8();
      }
      boolean surrogate =
          codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
      if (surrogate) {
        throw invalidUtf8();
      }
      return codePoint;
    }

    private SkillValidationException invalidUtf8() {
      return failure(SkillValidationCode.INVALID_UTF8, safeName + " is not valid UTF-8");
    }
  }
}
