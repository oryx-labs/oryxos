package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillContentValidatorTest {

  @TempDir Path tempDir;

  private final SkillContentValidator validator = new SkillContentValidator();

  @Test
  void returnsSortedContentStatsAndExcludesReservedSidecars() throws Exception {
    Path skill = validSkill("weather");
    Files.createDirectories(skill.resolve("references"));
    Files.writeString(skill.resolve("references/z-last.md"), "z");
    Files.writeString(skill.resolve("references/a-first.md"), "aa");
    Files.write(skill.resolve(".oryxos-disabled"), new byte[0]);
    Files.writeString(
        skill.resolve(".oryxos-origin.yml"),
        """
        schemaVersion: 1
        sourceType: upload
        originalFilename: weather.zip
        importedAt: 2026-07-22T10:30:00Z
        """);

    SkillContentValidator.ContentStats stats = validator.validate(skill, SkillLimits.defaults());

    assertEquals(
        java.util.List.of("SKILL.md", "references/a-first.md", "references/z-last.md"),
        stats.resources());
    assertEquals(3, stats.fileCount());
    assertEquals(Files.size(skill.resolve("SKILL.md")) + 3, stats.totalBytes());
    assertThrows(UnsupportedOperationException.class, () -> stats.resources().add("later"));
  }

  @Test
  void trustedRootSidecarsDoNotConsumePackageEntryOrByteBudgets() throws Exception {
    Path skill = validSkill("budgeted");
    Files.createFile(skill.resolve(".oryxos-disabled"));
    Files.writeString(
        skill.resolve(".oryxos-origin.yml"),
        """
        schemaVersion: 1
        sourceType: upload
        originalFilename: budgeted.zip
        importedAt: 2026-07-22T10:30:00Z
        """);
    long skillBytes = Files.size(skill.resolve("SKILL.md"));

    SkillContentValidator.ContentStats stats =
        validator.validate(skill, limits(1, 8, 512, skillBytes, skillBytes));

    assertEquals(java.util.List.of("SKILL.md"), stats.resources());
    assertEquals(skillBytes, stats.totalBytes());
  }

  @Test
  void validatesOriginalFilenameLengthByUnicodeCodePoint() throws Exception {
    Path skill = validSkill("unicode-origin");
    String originalFilename = "😀".repeat(200) + ".zip";
    Files.writeString(
        skill.resolve(".oryxos-origin.yml"),
        "schemaVersion: 1\nsourceType: upload\noriginalFilename: "
            + originalFilename
            + "\nimportedAt: 2026-07-22T10:30:00Z\n");

    SkillContentValidator.ContentStats stats = validator.validate(skill, SkillLimits.defaults());

    assertEquals(java.util.List.of("SKILL.md"), stats.resources());
  }

  @Test
  void rejectsEntrypointAndResourceLinksWithoutFollowingThem() throws Exception {
    Path outside = Files.writeString(tempDir.resolve("outside.md"), "secret");
    Path linkedEntry = Files.createDirectories(tempDir.resolve("linked-entry"));
    Path linkedResource = validSkill("linked-resource");
    try {
      Files.createSymbolicLink(linkedEntry.resolve("SKILL.md"), outside);
      Files.createSymbolicLink(linkedResource.resolve("secret.md"), outside);
    } catch (UnsupportedOperationException | IOException e) {
      Assumptions.abort("symbolic links are unavailable in this filesystem");
    }

    assertCode(SkillValidationCode.LINK_NOT_ALLOWED, linkedEntry, SkillLimits.defaults());
    assertCode(SkillValidationCode.LINK_NOT_ALLOWED, linkedResource, SkillLimits.defaults());
  }

  @Test
  void rejectsNonCanonicalEntrypointCasingOnEveryFilesystem() throws Exception {
    Path skill = validSkill("entry-case");
    Files.delete(skill.resolve("SKILL.md"));
    Files.writeString(skill.resolve("skill.md"), "placeholder");

    assertCode(SkillValidationCode.MISSING_ENTRYPOINT, skill, SkillLimits.defaults());
  }

  @Test
  void rejectsUnicodeFormatAndLineSeparatorCharactersLikeTheZipImporter() throws Exception {
    for (int codePoint : new int[] {0x2028, 0x2029, 0x2066}) {
      Path skill = validSkill("unsafe-path-" + codePoint);
      Files.writeString(skill.resolve("bad" + Character.toString(codePoint) + ".md"), "content");

      assertCode(SkillValidationCode.INVALID_PATH, skill, SkillLimits.defaults());
    }
  }

  @Test
  void rejectsSpecialFilesWhenTheFilesystemCanCreateThem() throws Exception {
    Path skill = validSkill("special");
    Path socket = skill.resolve("runtime.sock");
    try (ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      try {
        channel.bind(UnixDomainSocketAddress.of(socket));
      } catch (UnsupportedOperationException | IOException e) {
        Assumptions.abort("Unix domain sockets are unavailable in this filesystem");
      }

      assertCode(SkillValidationCode.SPECIAL_FILE_NOT_ALLOWED, skill, SkillLimits.defaults());
    }
  }

  @Test
  void validatesReservedFiles() throws Exception {
    Path nonEmptyDisabled = validSkill("bad-disabled");
    Files.writeString(nonEmptyDisabled.resolve(".oryxos-disabled"), "true");
    Path invalidOrigin = validSkill("bad-origin");
    Files.writeString(
        invalidOrigin.resolve(".oryxos-origin.yml"),
        "schemaVersion: 1\nsourceType: upload\noriginalFilename: ../secret.zip\n");
    Path nestedReserved = validSkill("nested-reserved");
    Files.createDirectories(nestedReserved.resolve("references"));
    Files.write(nestedReserved.resolve("references/.oryxos-disabled"), new byte[0]);

    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, nonEmptyDisabled, SkillLimits.defaults());
    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, invalidOrigin, SkillLimits.defaults());
    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, nestedReserved, SkillLimits.defaults());
  }

  @Test
  void caseVariantsOfReservedSidecarsAreInvalidOnEveryFilesystem() throws Exception {
    Path disabledVariant = validSkill("case-disabled");
    Files.writeString(disabledVariant.resolve(".ORYXOS-disabled"), "not-a-marker");
    Path originVariant = validSkill("case-origin");
    Files.writeString(originVariant.resolve(".OryxOS-origin.yml"), "untrusted: true\n");
    Path nestedVariant = validSkill("case-nested");
    Files.createDirectories(nestedVariant.resolve("references/.ORYXOS-private"));

    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, disabledVariant, SkillLimits.defaults());
    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, originVariant, SkillLimits.defaults());
    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, nestedVariant, SkillLimits.defaults());
  }

  @Test
  void rejectsReservedDirectoriesBeforeVisitingTheirChildren() throws Exception {
    Path markerDirectory = validSkill("marker-directory");
    Files.createDirectories(markerDirectory.resolve(".oryxos-disabled"));
    Path originDirectoryWithChild = validSkill("origin-directory");
    Path originDirectory =
        Files.createDirectories(originDirectoryWithChild.resolve(".oryxos-origin.yml"));
    Files.writeString(originDirectory.resolve("child.txt"), "must not be visited as content");

    assertCode(SkillValidationCode.RESERVED_FILE_INVALID, markerDirectory, SkillLimits.defaults());
    assertCode(
        SkillValidationCode.RESERVED_FILE_INVALID,
        originDirectoryWithChild,
        SkillLimits.defaults());
  }

  @Test
  void enforcesEntryDepthPathAndByteBudgets() throws Exception {
    Path tooMany = validSkill("too-many");
    Files.writeString(tooMany.resolve("one.txt"), "1");
    Files.writeString(tooMany.resolve("two.txt"), "2");
    Path tooDeep = validSkill("too-deep");
    Path deep = Files.createDirectories(tooDeep.resolve("a/b/c"));
    Files.writeString(deep.resolve("value.txt"), "x");
    Path longPath = validSkill("long-path");
    Files.writeString(longPath.resolve("very-long-resource-name.txt"), "x");
    Path largeFile = validSkill("large-file");
    Files.writeString(largeFile.resolve("resource.txt"), "x".repeat(256));
    Path largePackage = validSkill("large-package");
    Files.writeString(largePackage.resolve("one.txt"), "x".repeat(32));
    Files.writeString(largePackage.resolve("two.txt"), "x".repeat(32));

    assertCode(SkillValidationCode.TOO_MANY_ENTRIES, tooMany, limits(2, 8, 512, 1024, 4096));
    assertCode(SkillValidationCode.PATH_TOO_DEEP, tooDeep, limits(128, 2, 512, 1024, 4096));
    assertCode(SkillValidationCode.PATH_TOO_LONG, longPath, limits(128, 8, 12, 1024, 4096));
    assertCode(SkillValidationCode.FILE_TOO_LARGE, largeFile, limits(128, 8, 512, 100, 4096));
    assertCode(SkillValidationCode.PACKAGE_TOO_LARGE, largePackage, limits(128, 8, 512, 100, 100));
  }

  @Test
  void rejectsBlockedExtensionsAndMagicFromOnlyTheBoundedPrefix() throws Exception {
    Path extension = validSkill("extension");
    Files.writeString(extension.resolve("nested.zip"), "not really a zip");
    Path magic = validSkill("magic");
    Files.write(magic.resolve("payload.dat"), new byte[] {'P', 'K', 3, 4, 1, 2, 3});

    assertCode(SkillValidationCode.UNSUPPORTED_FILE_TYPE, extension, SkillLimits.defaults());
    assertCode(SkillValidationCode.UNSUPPORTED_FILE_TYPE, magic, SkillLimits.defaults());
  }

  @Test
  void errorsExposeOnlyRelativeMembers() throws Exception {
    Path skill = validSkill("private-skill");
    Files.writeString(skill.resolve("payload.jar"), "blocked");

    SkillValidationException error =
        assertThrows(
            SkillValidationException.class,
            () -> validator.validate(skill, SkillLimits.defaults()));

    assertFalse(error.getMessage().contains(tempDir.toString()));
    assertEquals(SkillValidationCode.UNSUPPORTED_FILE_TYPE, error.code());
  }

  private Path validSkill(String name) throws IOException {
    Path skill = Files.createDirectories(tempDir.resolve(name));
    Files.writeString(
        skill.resolve("SKILL.md"), "---\nname: " + name + "\ndescription: test\n---\nbody");
    return skill;
  }

  private void assertCode(SkillValidationCode code, Path skill, SkillLimits limits) {
    SkillValidationException error =
        assertThrows(SkillValidationException.class, () -> validator.validate(skill, limits));
    assertEquals(code, error.code());
    assertFalse(error.getMessage().contains(tempDir.toString()));
  }

  private static SkillLimits limits(
      int maxEntries, int maxDepth, int maxPathChars, long maxFileBytes, long maxExpandedBytes) {
    SkillLimits defaults = SkillLimits.defaults();
    long maxSkillMarkdownBytes = Math.min(256, maxFileBytes);
    long maxFrontmatterBytes = Math.min(128, maxSkillMarkdownBytes);
    return new SkillLimits(
        defaults.maxArchiveBytes(),
        maxExpandedBytes,
        maxFileBytes,
        maxSkillMarkdownBytes,
        maxFrontmatterBytes,
        defaults.maxYamlNestingDepth(),
        maxEntries,
        maxDepth,
        maxPathChars,
        defaults.maxExpansionRatio(),
        defaults.maxSkillsPerAgent(),
        defaults.maxCandidatesPerAgent(),
        defaults.maxCatalogChars(),
        Duration.ofHours(24));
  }
}
