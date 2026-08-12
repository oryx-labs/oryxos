package io.oryxos.core.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillStoreTest {

  @TempDir Path root;

  @Test
  void writeAllRejectsIntermediateSymlinkEscape() throws Exception {
    Path outside = Files.createDirectories(root.resolveSibling("skill-store-outside"));
    Path skill = Files.createDirectories(root.resolve("skills/report"));
    Files.createSymbolicLink(skill.resolve("scripts"), outside);

    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillStore(root).writeAll("report", Map.of("scripts/pwned.sh", "bad")));
    assertFalse(Files.exists(outside.resolve("pwned.sh")));

    Path outsideFile = Files.writeString(outside.resolve("keep.md"), "keep");
    Files.createSymbolicLink(skill.resolve("REFERENCE.md"), outsideFile);
    assertThrows(
        IllegalArgumentException.class,
        () -> new SkillStore(root).writeAll("report", Map.of("REFERENCE.md", "bad")));
    assertEquals("keep", Files.readString(outsideFile));

    new SkillStore(root).writeAll("report", Map.of("examples/ok.md", "ok"));
    assertEquals("ok", Files.readString(skill.resolve("examples/ok.md")));
  }
}
