package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLoaderTest {

  @TempDir Path root;

  @Test
  @DisplayName("公共目录名、frontmatter name 和 description 必须完整一致")
  void metadataMustBeCompleteAndMatchDirectory() throws IOException {
    SkillLoader loader = new SkillLoader(root);
    Path mismatch = root.resolve("actual");
    Files.createDirectories(mismatch);
    Files.writeString(mismatch.resolve("SKILL.md"), "---\nname: other\ndescription: d\n---\nbody");
    assertThrows(IllegalArgumentException.class, () -> loader.deriveSkill(mismatch));

    assertThrows(
        IllegalArgumentException.class,
        () -> loader.parse("---\ndescription: d\n---\nbody", "fallback"));
    assertThrows(
        IllegalArgumentException.class, () -> loader.parse("---\nname: valid\n---\nbody", "valid"));
  }
}
