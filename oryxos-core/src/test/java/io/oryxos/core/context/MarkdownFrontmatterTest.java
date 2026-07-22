package io.oryxos.core.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.skill.SkillValidationCode;
import io.oryxos.core.skill.SkillValidationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MarkdownFrontmatterTest {

  private static final long MAX_SKILL_BYTES = 4096;
  private static final long MAX_FRONTMATTER_BYTES = 1024;

  @TempDir Path tempDir;

  @ParameterizedTest
  @MethodSource("validDocuments")
  void normalizesLinesAndFindsStandaloneFences(String document, String expectedYaml)
      throws Exception {
    Path file = write("SKILL.md", document.getBytes(StandardCharsets.UTF_8));

    MarkdownFrontmatter.Parsed parsed =
        MarkdownFrontmatter.read(file, MAX_SKILL_BYTES, MAX_FRONTMATTER_BYTES);

    assertEquals(expectedYaml, parsed.yaml());
    assertTrue(parsed.hasNonBlankPrompt());
    assertTrue(parsed.bodyStart() > parsed.yaml().length());
  }

  private static Stream<Arguments> validDocuments() {
    return Stream.of(
        Arguments.of(
            "---\r\nname: weather\r\ndescription: CRLF\r\n---\r\n正文",
            "name: weather\ndescription: CRLF"),
        Arguments.of(
            "---\rname: weather\rdescription: CR\r---\r正文", "name: weather\ndescription: CR"),
        Arguments.of(
            "\ufeff\n\n---   \nname: weather\ndescription: BOM\n \t--- \t\n\n正文",
            "name: weather\ndescription: BOM"),
        Arguments.of(
            "---\nname: weather\ndescription: 中文😀\n---\n正文😀", "name: weather\ndescription: 中文😀"),
        Arguments.of("---\nname: weather\n---\n---", "name: weather"));
  }

  @ParameterizedTest
  @MethodSource("invalidFenceDocuments")
  void reportsStableFenceErrors(String document, SkillValidationCode expectedCode)
      throws Exception {
    Path file = write("SKILL.md", document.getBytes(StandardCharsets.UTF_8));

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () -> MarkdownFrontmatter.read(file, MAX_SKILL_BYTES, MAX_FRONTMATTER_BYTES));

    assertEquals(expectedCode, error.code());
    assertFalse(error.getMessage().contains(tempDir.toString()));
  }

  private static Stream<Arguments> invalidFenceDocuments() {
    return Stream.of(
        Arguments.of("plain body", SkillValidationCode.MISSING_FRONTMATTER),
        Arguments.of(" \n---\nname: weather\n---\nbody", SkillValidationCode.MISSING_FRONTMATTER),
        Arguments.of("  ---\nname: weather\n---\nbody", SkillValidationCode.MISSING_FRONTMATTER),
        Arguments.of("---yaml\nname: weather\n---\nbody", SkillValidationCode.MISSING_FRONTMATTER),
        Arguments.of("---", SkillValidationCode.MISSING_FRONTMATTER),
        Arguments.of("---\nname: weather\nbody", SkillValidationCode.UNCLOSED_FRONTMATTER),
        Arguments.of("---\nname: weather\n---\n\n\t ", SkillValidationCode.EMPTY_PROMPT),
        Arguments.of("---\nname: weather\n---\n\u00a0", SkillValidationCode.EMPTY_PROMPT));
  }

  @Test
  void rejectsMalformedUtf8WithoutLeakingTheAbsolutePath() throws Exception {
    byte[] prefix = "---\nname: weather\ndescription: ".getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\n---\nbody".getBytes(StandardCharsets.UTF_8);
    byte[] document = new byte[prefix.length + 1 + suffix.length];
    System.arraycopy(prefix, 0, document, 0, prefix.length);
    document[prefix.length] = (byte) 0x80;
    System.arraycopy(suffix, 0, document, prefix.length + 1, suffix.length);
    Path file = write("private-SKILL.md", document);

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () -> MarkdownFrontmatter.read(file, MAX_SKILL_BYTES, MAX_FRONTMATTER_BYTES));

    assertEquals(SkillValidationCode.INVALID_UTF8, error.code());
    assertTrue(error.getMessage().contains("private-SKILL.md"));
    assertFalse(error.getMessage().contains(tempDir.toString()));
  }

  @Test
  void sanitizesAllControlAndFormatCharactersFromReportedFilenames() {
    byte[] document = "plain body".getBytes(StandardCharsets.UTF_8);

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () ->
                MarkdownFrontmatter.read(
                    new ByteArrayInputStream(document),
                    "bad\t\u2028\u2066name/entry",
                    document.length,
                    MAX_SKILL_BYTES,
                    MAX_FRONTMATTER_BYTES));

    assertEquals(SkillValidationCode.MISSING_FRONTMATTER, error.code());
    assertFalse(error.getMessage().contains("\t"));
    assertFalse(error.getMessage().contains("\u2028"));
    assertFalse(error.getMessage().contains("\u2066"));
    assertFalse(error.getMessage().contains("/entry"));
  }

  @Test
  void enforcesRawFileAndFrontmatterByteLimits() throws Exception {
    Path oversizedFile =
        write(
            "large-SKILL.md",
            "---\nname: weather\n---\nbody-too-long".getBytes(StandardCharsets.UTF_8));
    Path oversizedFrontmatter =
        write(
            "frontmatter-SKILL.md",
            "---\r\ndescription: 中文中文\r\n---\r\nbody".getBytes(StandardCharsets.UTF_8));

    SkillValidationException fileError =
        assertThrows(
            SkillValidationException.class,
            () -> MarkdownFrontmatter.read(oversizedFile, 16, MAX_FRONTMATTER_BYTES));
    SkillValidationException frontmatterError =
        assertThrows(
            SkillValidationException.class,
            () -> MarkdownFrontmatter.read(oversizedFrontmatter, MAX_SKILL_BYTES, 20));

    assertEquals(SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, fileError.code());
    assertEquals(SkillValidationCode.FRONTMATTER_TOO_LARGE, frontmatterError.code());
  }

  @Test
  void stopsAfterTheFirstCompleteNonWhitespaceCodePointAndAlwaysClosesTheStream() {
    byte[] prefix =
        "---\nname: weather\ndescription: test\n---\n\n😀".getBytes(StandardCharsets.UTF_8);
    CloseTrackingInputStream input = new CloseTrackingInputStream(prefix);

    MarkdownFrontmatter.Parsed parsed =
        MarkdownFrontmatter.read(
            input, "SKILL.md", prefix.length + 100, MAX_SKILL_BYTES, MAX_FRONTMATTER_BYTES);

    assertTrue(parsed.hasNonBlankPrompt());
    assertTrue(input.closed);
    assertEquals(prefix.length, input.bytesRead);
  }

  @Test
  void doesNotTrustAnUnderreportedStreamLength() {
    byte[] document =
        ("---\nname: weather\ndescription: " + "x".repeat(80) + "\n---\nbody")
            .getBytes(StandardCharsets.UTF_8);
    CloseTrackingInputStream input = new CloseTrackingInputStream(document);

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () -> MarkdownFrontmatter.read(input, "SKILL.md", 1, 48, 1024));

    assertEquals(SkillValidationCode.SKILL_MARKDOWN_TOO_LARGE, error.code());
    assertTrue(input.closed);
  }

  @Test
  void preservesCompleteSupplementaryCodePointsAtHeaderAndBodyBoundaries() throws Exception {
    Path file =
        write("emoji-SKILL.md", "---\ndescription: 😀\n---\n😀".getBytes(StandardCharsets.UTF_8));

    MarkdownFrontmatter.Parsed parsed =
        MarkdownFrontmatter.read(file, MAX_SKILL_BYTES, MAX_FRONTMATTER_BYTES);

    assertEquals(1, "😀".codePointCount(0, "😀".length()));
    assertTrue(parsed.yaml().contains("😀"));
    assertTrue(parsed.hasNonBlankPrompt());
  }

  @Test
  void parsedHeaderNeverRetainsPromptOrResourceContent() {
    Set<String> components =
        List.of(MarkdownFrontmatter.Parsed.class.getRecordComponents()).stream()
            .map(component -> component.getName())
            .collect(Collectors.toSet());

    assertEquals(Set.of("yaml", "bodyStart", "hasNonBlankPrompt"), components);
  }

  private Path write(String name, byte[] content) throws IOException {
    Path file = tempDir.resolve(name);
    Files.write(file, content);
    return file;
  }

  private static final class CloseTrackingInputStream extends InputStream {

    private final byte[] bytes;
    private int offset;
    private int bytesRead;
    private boolean closed;

    private CloseTrackingInputStream(byte[] bytes) {
      this.bytes = bytes.clone();
    }

    @Override
    public int read() {
      if (offset >= bytes.length) {
        throw new AssertionError("parser read past the first non-whitespace code point");
      }
      bytesRead++;
      return bytes[offset++] & 0xff;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
