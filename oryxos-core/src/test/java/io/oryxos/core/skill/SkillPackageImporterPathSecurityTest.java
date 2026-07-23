package io.oryxos.core.skill;

import static io.oryxos.core.skill.SkillPackageTestSupport.assertStagingEmpty;
import static io.oryxos.core.skill.SkillPackageTestSupport.file;
import static io.oryxos.core.skill.SkillPackageTestSupport.limits;
import static io.oryxos.core.skill.SkillPackageTestSupport.replaceAsciiName;
import static io.oryxos.core.skill.SkillPackageTestSupport.validSkillMarkdown;
import static io.oryxos.core.skill.SkillPackageTestSupport.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SkillPackageImporterPathSecurityTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T10:30:00Z"), ZoneOffset.UTC);

  @TempDir Path tempDir;

  @ParameterizedTest(name = "rejects unsafe ZIP path {0}")
  @MethodSource("unsafePaths")
  void rejectsUnsafePosixPaths(String entryName) throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(".oryxos"));
    SkillPackageImporter importer = new SkillPackageImporter(root, SkillLimits.defaults(), CLOCK);
    String writableName = entryName.indexOf('\\') >= 0 ? entryName.replace('\\', '/') : entryName;
    byte[] archive =
        zip(file("SKILL.md", validSkillMarkdown("weather")), file(writableName, "untrusted"));
    if (!writableName.equals(entryName)) {
      archive = replaceAsciiName(archive, writableName, entryName);
    }
    byte[] uploadedArchive = archive;

    SkillImportException error =
        assertThrows(
            SkillImportException.class,
            () -> importer.prepare(new ByteArrayInputStream(uploadedArchive), "unsafe.zip"));

    assertEquals("INVALID_ENTRY_PATH", error.reasonCode());
    assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    assertFalse(error.getMessage().contains(root.toString()));
    assertStagingEmpty(root);
  }

  static Stream<Arguments> unsafePaths() {
    return Stream.of(
        Arguments.of("../escape.txt"),
        Arguments.of("/absolute.txt"),
        Arguments.of("C:/drive.txt"),
        Arguments.of("C:drive-relative.txt"),
        Arguments.of("//server/share.txt"),
        Arguments.of("references\\windows.txt"),
        Arguments.of("bad\u0000name.txt"),
        Arguments.of("references/unsafe\u202ename.txt"),
        Arguments.of("references//empty.txt"),
        Arguments.of("references/./dot.txt"),
        Arguments.of("references/../escape.txt"));
  }

  @Test
  void enforcesPathDepthAndUnicodeCharacterLength() throws Exception {
    Path depthRoot = Files.createDirectories(tempDir.resolve("depth/.oryxos"));
    SkillLimits depthLimits =
        limits(1_000_000, 1_000_000, 100_000, 50_000, 20_000, 128, 2, 512, 100);
    SkillPackageImporter depthImporter = new SkillPackageImporter(depthRoot, depthLimits, CLOCK);
    byte[] deep =
        zip(file("SKILL.md", validSkillMarkdown("weather")), file("one/two/three.txt", "deep"));

    SkillImportException depthError =
        assertThrows(
            SkillImportException.class,
            () -> depthImporter.prepare(new ByteArrayInputStream(deep), "deep.zip"));
    assertEquals("PATH_TOO_DEEP", depthError.reasonCode());
    assertStagingEmpty(depthRoot);

    Path lengthRoot = Files.createDirectories(tempDir.resolve("length/.oryxos"));
    SkillLimits lengthLimits =
        limits(1_000_000, 1_000_000, 100_000, 50_000, 20_000, 128, 8, 12, 100);
    SkillPackageImporter lengthImporter = new SkillPackageImporter(lengthRoot, lengthLimits, CLOCK);
    byte[] longPath =
        zip(file("SKILL.md", validSkillMarkdown("weather")), file("references/天气预报说明.md", "long"));

    SkillImportException lengthError =
        assertThrows(
            SkillImportException.class,
            () -> lengthImporter.prepare(new ByteArrayInputStream(longPath), "long.zip"));
    assertEquals("PATH_TOO_LONG", lengthError.reasonCode());
    assertStagingEmpty(lengthRoot);
  }

  @ParameterizedTest
  @MethodSource("collidingEntries")
  void rejectsRawNfcAndCaseFoldedDuplicateEntries(String firstName, String secondName)
      throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(".oryxos"));
    SkillPackageImporter importer = new SkillPackageImporter(root, SkillLimits.defaults(), CLOCK);
    byte[] archive =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file(firstName, "first"),
            file(secondName, "second"));

    SkillImportException error =
        assertThrows(
            SkillImportException.class,
            () -> importer.prepare(new ByteArrayInputStream(archive), "duplicate.zip"));

    assertEquals("DUPLICATE_ENTRY", error.reasonCode());
    assertStagingEmpty(root);
  }

  static Stream<Arguments> collidingEntries() {
    return Stream.of(
        Arguments.of("references/same.txt", "references/same.txt"),
        Arguments.of("references/Guide.md", "references/guide.md"),
        Arguments.of("references/caf\u00e9.md", "references/cafe\u0301.md"),
        Arguments.of("References/a.md", "references/b.md"),
        Arguments.of("references/caf\u00e9/a.md", "references/cafe\u0301/b.md"));
  }

  @Test
  void rejectsMultipleTopLevelWrappers() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(".oryxos"));
    SkillPackageImporter importer = new SkillPackageImporter(root, SkillLimits.defaults(), CLOCK);
    byte[] archive =
        zip(
            file("weather/SKILL.md", validSkillMarkdown("weather")),
            file("other/SKILL.md", validSkillMarkdown("other")));

    SkillImportException error =
        assertThrows(
            SkillImportException.class,
            () -> importer.prepare(new ByteArrayInputStream(archive), "multiple.zip"));

    assertEquals("INVALID_PACKAGE_SHAPE", error.reasonCode());
    assertStagingEmpty(root);
  }
}
