package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboundMediaJanitorTest {

  @TempDir Path root;

  @Test
  @DisplayName("TTL 过期目录被删")
  void sweepsExpired() throws Exception {
    Path oldDir = root.resolve("old-msg");
    Files.createDirectories(oldDir);
    Files.writeString(oldDir.resolve("a.bin"), "x");
    Files.setLastModifiedTime(
        oldDir.resolve("a.bin"),
        java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(48))));
    Files.setLastModifiedTime(
        oldDir, java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(48))));

    InboundMediaJanitor janitor = new InboundMediaJanitor(Duration.ofHours(24), 0L);
    janitor.sweep(root, Instant.now());
    assertFalse(Files.exists(oldDir));
  }

  @Test
  @DisplayName("超限 copyLimited 删除目标")
  void copyLimitedEnforcesMax() throws IOException {
    Path target = root.resolve("big.bin");
    byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
    assertThrows(
        IOException.class,
        () -> LimitedMediaWriter.copyLimited(new ByteArrayInputStream(data), target, 5));
    assertFalse(Files.exists(target));
  }
}
