package io.oryxos.core.skill;

import static io.oryxos.core.skill.SkillPackageTestSupport.assertStagingEmpty;
import static io.oryxos.core.skill.SkillPackageTestSupport.file;
import static io.oryxos.core.skill.SkillPackageTestSupport.limits;
import static io.oryxos.core.skill.SkillPackageTestSupport.underreportUncompressedSize;
import static io.oryxos.core.skill.SkillPackageTestSupport.validSkillMarkdown;
import static io.oryxos.core.skill.SkillPackageTestSupport.zip;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillPackageImporterLimitsTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-22T10:30:00Z"), ZoneOffset.UTC);

  @TempDir Path tempDir;

  @Test
  void mapsActualArchiveBytesToTooLarge() throws Exception {
    byte[] archive = zip(file("SKILL.md", validSkillMarkdown("weather")));
    SkillLimits configured = limits(64, 4096, 2048, 1024, 512, 128, 8, 512, 100);
    assertTooLarge(archive, configured, "ARCHIVE_TOO_LARGE");
  }

  @Test
  void mapsActualExpandedAndSingleFileBytesToTooLarge() throws Exception {
    byte[] expanded =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file("one.txt", "a".repeat(100)),
            file("two.txt", "b".repeat(100)));
    SkillLimits expandedLimits = limits(4096, 220, 150, 128, 96, 128, 8, 512, 100);
    assertTooLarge(expanded, expandedLimits, "EXPANDED_TOO_LARGE");

    byte[] single =
        zip(file("SKILL.md", validSkillMarkdown("weather")), file("large.txt", "x".repeat(256)));
    SkillLimits fileLimits = limits(4096, 1024, 128, 128, 96, 128, 8, 512, 100);
    assertTooLarge(single, fileLimits, "FILE_TOO_LARGE");
  }

  @Test
  void mapsSkillMarkdownAndFrontmatterBudgetsToTooLarge() throws Exception {
    byte[] largeSkill =
        zip(
            file(
                "SKILL.md",
                new String(validSkillMarkdown("weather"), StandardCharsets.UTF_8)
                    .concat("x".repeat(256))
                    .getBytes(StandardCharsets.UTF_8)));
    SkillLimits skillLimits = limits(4096, 2048, 1024, 128, 96, 128, 8, 512, 100);
    assertTooLarge(largeSkill, skillLimits, "SKILL_MARKDOWN_TOO_LARGE");

    String frontmatter =
        "---\nname: weather\ndescription: " + "a".repeat(180) + "\n---\n\nPrompt.\n";
    byte[] largeFrontmatter = zip(file("SKILL.md", frontmatter));
    SkillLimits frontmatterLimits = limits(4096, 2048, 1024, 512, 80, 128, 8, 512, 100);
    assertTooLarge(largeFrontmatter, frontmatterLimits, "FRONTMATTER_TOO_LARGE");
  }

  @Test
  void mapsEntryCountAndExpansionRatioToTooLarge() throws Exception {
    byte[] entries =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file("one.txt", "one"),
            file("two.txt", "two"));
    SkillLimits entryLimits = limits(4096, 2048, 1024, 512, 256, 2, 8, 512, 100);
    assertTooLarge(entries, entryLimits, "TOO_MANY_ENTRIES");

    byte[] bombLike =
        zip(
            file("SKILL.md", validSkillMarkdown("weather")),
            file("repeated.txt", "a".repeat(16_384)));
    SkillLimits ratioLimits = limits(4096, 20_000, 18_000, 1024, 512, 128, 8, 512, 2);
    assertTooLarge(bombLike, ratioLimits, "EXPANSION_RATIO_EXCEEDED");
  }

  @Test
  void ignoresUnderreportedHeaderSizeAndCountsStreamedBytes() throws Exception {
    byte[] archive =
        zip(file("SKILL.md", validSkillMarkdown("weather")), file("large.txt", "x".repeat(512)));
    byte[] lyingArchive = underreportUncompressedSize(archive, 1);
    SkillLimits configured = limits(4096, 2048, 256, 128, 96, 128, 8, 512, 100);

    assertTooLarge(lyingArchive, configured, "FILE_TOO_LARGE");
  }

  private void assertTooLarge(byte[] archive, SkillLimits configured, String reasonCode)
      throws Exception {
    Path root = Files.createDirectories(tempDir.resolve(reasonCode + "/.oryxos"));
    SkillPackageImporter importer = new SkillPackageImporter(root, configured, CLOCK);

    SkillPackageTooLargeException error =
        assertThrows(
            SkillPackageTooLargeException.class,
            () -> importer.prepare(new ByteArrayInputStream(archive), "large.zip"));

    assertEquals(reasonCode, error.reasonCode());
    assertStagingEmpty(root);
  }
}
