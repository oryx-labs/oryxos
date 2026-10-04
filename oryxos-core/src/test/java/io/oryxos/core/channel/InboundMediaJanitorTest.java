package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  /** 落一个占 {@code bytes} 字节的目录，mtime 设为 {@code age} 之前。 */
  private static Path messageDir(Path parent, String name, int bytes, Duration age)
      throws IOException {
    Path dir = Files.createDirectories(parent.resolve(name));
    Path file = dir.resolve("payload.bin");
    Files.write(file, new byte[bytes]);
    var stamp = java.nio.file.attribute.FileTime.from(Instant.now().minus(age));
    Files.setLastModifiedTime(file, stamp);
    Files.setLastModifiedTime(dir, stamp);
    return dir;
  }

  @Test
  @DisplayName("ensureQuotaOrThrow 在节流窗口内也必须真扫一遍，不得谎报超配额")
  void quotaCheckSweepsEvenInsideTheThrottleWindow() throws Exception {
    Path mediaRoot = Files.createDirectories(root.resolve("media"));
    long cap = 100L * 1024;
    InboundMediaJanitor janitor = new InboundMediaJanitor(Duration.ofHours(24), cap);

    // 一个过期目录 + 一个新目录：合计 120KB > 100KB，但扫一遍就能降到 60KB。
    messageDir(mediaRoot, "expired", 60 * 1024, Duration.ofHours(48));
    messageDir(mediaRoot, "fresh", 60 * 1024, Duration.ofMinutes(1));

    // 第一次：扫掉 expired，回到配额内；此刻节流时间戳被置为 now。
    janitor.ensureQuotaOrThrow(mediaRoot);

    // 又一个新目录进来，根再次超配额，而此刻仍在上一次的节流窗口内。
    messageDir(mediaRoot, "fresh2", 60 * 1024, Duration.ofMinutes(1));

    // 走周期节流入口的话这一次会什么都不做、随后直接抛「超过配额」——
    // 而空间本来是腾得出来的（fresh 和 fresh2 里最旧的那个会被削掉）。
    janitor.ensureQuotaOrThrow(mediaRoot);
  }

  @Test
  @DisplayName("并发的按需清理：后者等前者扫完，不会读到扫描中途的大小")
  void concurrentQuotaChecksDoNotReadMidScanSizes() throws Exception {
    Path mediaRoot = Files.createDirectories(root.resolve("media3"));
    long cap = 100L * 1024;
    InboundMediaJanitor janitor = new InboundMediaJanitor(Duration.ofHours(24), cap);

    // 8000 个过期目录：一次 sweep 要跑一会儿，制造出「扫描进行中」的窗口。
    for (int i = 0; i < 8000; i++) {
      Path dir = Files.createDirectories(mediaRoot.resolve("e" + i));
      Files.write(dir.resolve("p.bin"), new byte[1]);
      var stamp = java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofHours(48)));
      Files.setLastModifiedTime(dir.resolve("p.bin"), stamp);
      Files.setLastModifiedTime(dir, stamp);
    }
    // 再加一个新鲜大目录，保证扫完之后仍在配额内。
    messageDir(mediaRoot, "fresh", 60 * 1024, Duration.ofMinutes(1));

    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.List<Throwable> failures =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    Runnable call =
        () -> {
          try {
            go.await();
            janitor.ensureQuotaOrThrow(mediaRoot);
          } catch (Throwable t) {
            failures.add(t);
          }
        };
    Thread a = new Thread(call);
    Thread b = new Thread(call);
    a.start();
    b.start();
    go.countDown();
    a.join(30_000);
    b.join(30_000);

    assertTrue(
        failures.isEmpty(),
        "两次并发调用都不该抛 —— 空间腾得出来；实际: " + failures.stream().map(Throwable::getMessage).toList());
  }

  @Test
  @DisplayName("节流窗口：窗口内不重复清理，窗口过后恢复")
  void sweepIfDueRespectsTheThrottleWindow() throws Exception {
    Path mediaRoot = Files.createDirectories(root.resolve("media4"));
    // 窗口取 200ms 以便在测试里跨过；生产固定 60s。
    InboundMediaJanitor janitor =
        new InboundMediaJanitor(
            Duration.ofHours(24), 0L, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200));

    Path first = messageDir(mediaRoot, "expired-a", 16, Duration.ofHours(48));
    janitor.sweepIfDue(mediaRoot);
    assertFalse(Files.exists(first), "窗口到期后第一次调用应当清理");

    Path second = messageDir(mediaRoot, "expired-b", 16, Duration.ofHours(48));
    janitor.sweepIfDue(mediaRoot);
    assertTrue(Files.exists(second), "窗口内的调用应当被节流掉");

    Thread.sleep(250);
    janitor.sweepIfDue(mediaRoot);
    assertFalse(Files.exists(second), "窗口过后应当恢复清理");
  }
}
