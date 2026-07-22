package io.oryxos.core.skill;

import static io.oryxos.core.skill.SkillPackageTestSupport.assertStagingEmpty;
import static io.oryxos.core.skill.SkillPackageTestSupport.directory;
import static io.oryxos.core.skill.SkillPackageTestSupport.file;
import static io.oryxos.core.skill.SkillPackageTestSupport.validSkillMarkdown;
import static io.oryxos.core.skill.SkillPackageTestSupport.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillPackageImporterTest {

  private static final Instant NOW = Instant.parse("2026-07-22T10:30:00Z");

  @TempDir Path tempDir;

  @Test
  void preparesShapeAAsEnabledWithSanitizedOrigin() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("shape-a/.oryxos"));
    SkillPackageImporter importer = importer(root, SkillLimits.defaults());
    byte[] archive =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file("references/guide.md", "forecast guide"));

    PreparedSkill prepared =
        importer.prepare(
            new ByteArrayInputStream(archive),
            "C:\\fakepath\\../../weather\u202e-upload\u0001.zip");

    assertEquals("weather", prepared.directoryName());
    assertEquals("weather", prepared.packageRoot().getFileName().toString());
    assertEquals("weather", prepared.metadata().name());
    assertEquals(SkillSource.UPLOAD, prepared.origin().sourceType());
    assertEquals("weather_-upload_.zip", prepared.origin().originalFilename());
    assertEquals(NOW, prepared.origin().importedAt());
    assertTrue(Files.isRegularFile(prepared.packageRoot().resolve("SKILL.md")));
    assertTrue(Files.isRegularFile(prepared.packageRoot().resolve(".oryxos-origin.yml")));
    assertFalse(Files.exists(prepared.packageRoot().resolve(".oryxos-disabled")));
    assertEquals(
        java.util.List.of("SKILL.md", "references/guide.md"), prepared.contentStats().resources());
    String origin = Files.readString(prepared.packageRoot().resolve(".oryxos-origin.yml"));
    assertTrue(origin.contains("schemaVersion: 1"));
    assertTrue(origin.contains("sourceType: upload"));
    assertTrue(origin.contains("originalFilename: weather_-upload_.zip"));
    assertTrue(
        origin.contains("importedAt: '2026-07-22T10:30:00Z'")
            || origin.contains("importedAt: 2026-07-22T10:30:00Z"));

    importer.discard(prepared);
    importer.discard(prepared);
    assertStagingEmpty(root);
  }

  @Test
  void preparesShapeBAndAcceptsFatEntriesWithModeZero() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("shape-b/.oryxos"));
    SkillPackageImporter importer = importer(root, SkillLimits.defaults());
    byte[] archive =
        zip(
            directory("weather"),
            file("weather/SKILL.md", validSkillMarkdown("weather")),
            file("weather/scripts/check.sh", "#!/bin/sh\nexit 0\n"));

    PreparedSkill prepared = importer.prepare(new ByteArrayInputStream(archive), "weather.zip");

    assertEquals("weather", prepared.directoryName());
    assertTrue(Files.isRegularFile(prepared.packageRoot().resolve("scripts/check.sh")));
    importer.discard(prepared);
    assertStagingEmpty(root);
  }

  @Test
  void rejectsWrapperMetadataNameMismatchAndCleansStaging() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("mismatch/.oryxos"));
    SkillPackageImporter importer = importer(root, SkillLimits.defaults());
    byte[] archive = zip(file("weather/SKILL.md", validSkillMarkdown("forecast")));

    SkillImportException error =
        assertThrows(
            SkillImportException.class,
            () -> importer.prepare(new ByteArrayInputStream(archive), "mismatch.zip"));

    assertEquals("NAME_DIRECTORY_MISMATCH", error.reasonCode());
    assertFalse(error.getMessage().contains(root.toString()));
    assertStagingEmpty(root);
  }

  @Test
  void removesExpiredStagingWithoutFollowingLinksAndKeepsRecentEvents() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("cleanup/.oryxos"));
    SkillPackageImporter importer = importer(root, SkillLimits.defaults());
    byte[] archive = zip(file("SKILL.md", validSkillMarkdown("weather")));
    PreparedSkill expired = importer.prepare(new ByteArrayInputStream(archive), "expired.zip");
    PreparedSkill recent = importer.prepare(new ByteArrayInputStream(archive), "recent.zip");
    Path outside = Files.writeString(tempDir.resolve("outside.txt"), "keep");
    try {
      Files.createSymbolicLink(expired.stagingEventDir().resolve("outside-link"), outside);
    } catch (UnsupportedOperationException ignored) {
      // The timestamp/containment assertions still exercise cleanup on this filesystem.
    }
    Files.setLastModifiedTime(
        expired.stagingEventDir(),
        FileTime.from(NOW.minus(SkillLimits.defaults().stagingTtl()).minusSeconds(1)));
    Files.setLastModifiedTime(recent.stagingEventDir(), FileTime.from(NOW.minusSeconds(60)));

    importer.cleanupOrphans();

    assertFalse(Files.exists(expired.stagingEventDir(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(recent.stagingEventDir(), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    assertEquals("keep", Files.readString(outside));
    importer.discard(recent);
    assertStagingEmpty(root);
  }

  @Test
  void refusesCleanupThroughASymlinkedStagingAncestor() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("linked-staging/.oryxos"));
    SkillPackageImporter importer = importer(root, SkillLimits.defaults());
    byte[] archive = zip(file("SKILL.md", validSkillMarkdown("weather")));
    PreparedSkill prepared = importer.prepare(new ByteArrayInputStream(archive), "weather.zip");
    Path staging = root.resolve(".staging");
    Path realStaging = root.resolve(".staging-real");
    Files.move(staging, realStaging);
    try {
      Files.createSymbolicLink(staging, realStaging.getFileName());
    } catch (UnsupportedOperationException | IOException error) {
      Files.move(realStaging, staging);
      Assumptions.abort("symbolic links are unavailable in this filesystem");
    }

    SkillImportException discardError =
        assertThrows(SkillImportException.class, () -> importer.discard(prepared));
    SkillImportException cleanupError =
        assertThrows(SkillImportException.class, importer::cleanupOrphans);
    assertEquals("STAGING_UNSAFE", discardError.reasonCode());
    assertEquals("STAGING_UNSAFE", cleanupError.reasonCode());
    assertTrue(Files.exists(prepared.packageRoot(), java.nio.file.LinkOption.NOFOLLOW_LINKS));

    Files.delete(staging);
    Files.move(realStaging, staging);
    importer.discard(prepared);
    assertStagingEmpty(root);
  }

  @Test
  void mapsUnexpectedUploadIoToInternalRuntimeAndStillCleansStaging() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("io-failure/.oryxos"));
    SkillPackageImporter importer = importer(root, SkillLimits.defaults());
    InputStream failingUpload =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("sensitive transport detail");
          }
        };

    UncheckedIOException error =
        assertThrows(
            UncheckedIOException.class, () -> importer.prepare(failingUpload, "upload.zip"));

    assertEquals("Skill package I/O failed", error.getMessage());
    assertStagingEmpty(root);
  }

  private static SkillPackageImporter importer(Path root, SkillLimits limits) {
    return new SkillPackageImporter(root, limits, Clock.fixed(NOW, ZoneOffset.UTC));
  }
}
