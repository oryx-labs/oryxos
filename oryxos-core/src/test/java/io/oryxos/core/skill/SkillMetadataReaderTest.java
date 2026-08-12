package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillMetadataReaderTest {

  @TempDir Path root;

  @Test
  void readsOnlyRequiredMetadataAndValidatesBodyBoundary() throws IOException {
    Path valid = Files.createDirectories(root.resolve("report"));
    Files.writeString(
        valid.resolve("SKILL.md"), "---\nname: report\ndescription: 报告格式\n---\nSECRET-BODY");

    SkillMetadataReader.Metadata metadata = new SkillMetadataReader().read(valid);

    assertEquals("report", metadata.name());
    assertEquals("报告格式", metadata.description());
    assertEquals(2, metadata.getClass().getRecordComponents().length, "目录模型不得携带正文");

    Path empty = Files.createDirectories(root.resolve("empty"));
    Files.writeString(empty.resolve("SKILL.md"), "---\nname: empty\ndescription: empty\n---\n\n");
    assertThrows(IllegalArgumentException.class, () -> new SkillMetadataReader().read(empty));
  }

  @Test
  void rejectsMissingMetadataAndDirectoryNameMismatch() throws IOException {
    Path mismatch = Files.createDirectories(root.resolve("actual"));
    Files.writeString(mismatch.resolve("SKILL.md"), "---\nname: other\ndescription: x\n---\nbody");
    assertThrows(IllegalArgumentException.class, () -> new SkillMetadataReader().read(mismatch));

    Path missing = Files.createDirectories(root.resolve("missing"));
    Files.writeString(missing.resolve("SKILL.md"), "---\nname: missing\n---\nbody");
    assertThrows(IllegalArgumentException.class, () -> new SkillMetadataReader().read(missing));

    Path noFrontmatter = Files.createDirectories(root.resolve("plain"));
    Files.writeString(noFrontmatter.resolve("SKILL.md"), "plain body");
    assertThrows(
        IllegalArgumentException.class, () -> new SkillMetadataReader().read(noFrontmatter));

    Path unclosed = Files.createDirectories(root.resolve("unclosed"));
    Files.writeString(unclosed.resolve("SKILL.md"), "---\nname: unclosed\ndescription: x");
    assertThrows(IllegalArgumentException.class, () -> new SkillMetadataReader().read(unclosed));

    Path unreadable = Files.createDirectories(root.resolve("unreadable"));
    Files.write(unreadable.resolve("SKILL.md"), new byte[] {(byte) 0xC3, (byte) 0x28});
    assertThrows(UncheckedIOException.class, () -> new SkillMetadataReader().read(unreadable));
  }
}
