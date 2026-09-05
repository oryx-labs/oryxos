package io.oryxos.core.channel;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站媒体根目录 TTL + 总配额清理。环境变量：
 *
 * <ul>
 *   <li>{@code ORYXOS_INBOUND_MEDIA_TTL_HOURS}（默认 24）
 *   <li>{@code ORYXOS_INBOUND_MEDIA_MAX_MB}（默认 2048；0=不按配额删）
 * </ul>
 */
public final class InboundMediaJanitor {

  private static final Logger LOG = LoggerFactory.getLogger(InboundMediaJanitor.class);

  private static final Duration DEFAULT_TTL = Duration.ofHours(24);
  private static final long DEFAULT_MAX_BYTES = 2048L * 1024 * 1024;
  private static final long MIN_SWEEP_INTERVAL_MS = 60_000L;

  private final Duration ttl;
  private final long maxBytes;
  private final AtomicLong lastSweepMs = new AtomicLong(0L);

  public InboundMediaJanitor() {
    this(resolveTtl(), resolveMaxBytes());
  }

  InboundMediaJanitor(Duration ttl, long maxBytes) {
    this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? DEFAULT_TTL : ttl;
    this.maxBytes = Math.max(0L, maxBytes);
  }

  public static InboundMediaJanitor fromEnv() {
    return new InboundMediaJanitor();
  }

  /** 节流：同一进程内至少间隔 60s；删过期 message 目录，再按 mtime 旧优先削配额。 */
  public void sweepIfDue(Path mediaRoot) {
    if (mediaRoot == null || !Files.isDirectory(mediaRoot)) {
      return;
    }
    long now = System.currentTimeMillis();
    long prev = lastSweepMs.get();
    if (now - prev < MIN_SWEEP_INTERVAL_MS) {
      return;
    }
    if (!lastSweepMs.compareAndSet(prev, now)) {
      return;
    }
    try {
      sweep(mediaRoot, Instant.ofEpochMilli(now));
    } catch (RuntimeException e) {
      LOG.warn("入站媒体清理失败: {}", InboundMediaPaths.sanitizeLog(e.getMessage()));
    }
  }

  void sweep(Path mediaRoot, Instant now) {
    Instant cutoff = now.minus(ttl);
    List<DirStat> dirs = new ArrayList<>();
    try (var stream = Files.list(mediaRoot)) {
      stream
          .filter(Files::isDirectory)
          .forEach(
              dir -> {
                try {
                  DirStat stat = statDir(dir);
                  if (stat.newestMtime().isBefore(cutoff)) {
                    deleteRecursively(dir);
                    LOG.info("入站媒体 TTL 清理: {}", InboundMediaPaths.sanitizeLog(pathLabel(dir)));
                  } else {
                    dirs.add(stat);
                  }
                } catch (IOException e) {
                  LOG.debug(
                      "入站媒体目录扫描失败 {}: {}",
                      InboundMediaPaths.sanitizeLog(pathLabel(dir)),
                      InboundMediaPaths.sanitizeLog(e.getMessage()));
                }
              });
    } catch (IOException e) {
      LOG.warn("入站媒体根扫描失败: {}", InboundMediaPaths.sanitizeLog(e.getMessage()));
      return;
    }
    if (maxBytes <= 0) {
      return;
    }
    long total = dirs.stream().mapToLong(DirStat::bytes).sum();
    if (total <= maxBytes) {
      return;
    }
    dirs.sort(Comparator.comparing(DirStat::newestMtime));
    for (DirStat stat : dirs) {
      if (total <= maxBytes) {
        break;
      }
      try {
        deleteRecursively(stat.path());
        total -= stat.bytes();
        LOG.info(
            "入站媒体配额清理: {} ({} bytes)",
            InboundMediaPaths.sanitizeLog(pathLabel(stat.path())),
            stat.bytes());
      } catch (IOException e) {
        LOG.debug("入站媒体配额删除失败: {}", InboundMediaPaths.sanitizeLog(e.getMessage()));
      }
    }
  }

  private static String pathLabel(Path path) {
    if (path == null) {
      return "";
    }
    Path name = path.getFileName();
    return name == null ? path.toString() : name.toString();
  }

  /** 写盘前粗检：根已超配额则先 sweep，仍超则抛错。 */
  public void ensureQuotaOrThrow(Path mediaRoot) throws IOException {
    if (mediaRoot == null || maxBytes <= 0 || !Files.isDirectory(mediaRoot)) {
      return;
    }
    long total = sizeOf(mediaRoot);
    if (total <= maxBytes) {
      return;
    }
    sweepIfDue(mediaRoot);
    total = sizeOf(mediaRoot);
    if (total > maxBytes) {
      throw new IOException("入站媒体目录超过配额 " + maxBytes + " 字节（可调 ORYXOS_INBOUND_MEDIA_MAX_MB / TTL）");
    }
  }

  private static DirStat statDir(Path dir) throws IOException {
    AtomicLong bytes = new AtomicLong(0L);
    AtomicLong newest = new AtomicLong(0L);
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            bytes.addAndGet(attrs.size());
            newest.accumulateAndGet(attrs.lastModifiedTime().toMillis(), Math::max);
            return FileVisitResult.CONTINUE;
          }
        });
    long ms = newest.get();
    if (ms <= 0L) {
      ms = Files.getLastModifiedTime(dir).toMillis();
    }
    return new DirStat(dir, bytes.get(), Instant.ofEpochMilli(ms));
  }

  private static long sizeOf(Path root) throws IOException {
    AtomicLong bytes = new AtomicLong(0L);
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            bytes.addAndGet(attrs.size());
            return FileVisitResult.CONTINUE;
          }
        });
    return bytes.get();
  }

  private static void deleteRecursively(Path dir) throws IOException {
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.deleteIfExists(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
            Files.deleteIfExists(d);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static Duration resolveTtl() {
    String raw = System.getenv("ORYXOS_INBOUND_MEDIA_TTL_HOURS");
    if (raw == null || raw.isBlank()) {
      return DEFAULT_TTL;
    }
    try {
      long hours = Long.parseLong(raw.strip());
      if (hours <= 0) {
        return DEFAULT_TTL;
      }
      return Duration.ofHours(hours);
    } catch (NumberFormatException e) {
      return DEFAULT_TTL;
    }
  }

  private static long resolveMaxBytes() {
    String raw = System.getenv("ORYXOS_INBOUND_MEDIA_MAX_MB");
    if (raw == null || raw.isBlank()) {
      return DEFAULT_MAX_BYTES;
    }
    try {
      long mb = Long.parseLong(raw.strip());
      if (mb < 0) {
        return DEFAULT_MAX_BYTES;
      }
      return mb * 1024L * 1024L;
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_BYTES;
    }
  }

  private record DirStat(Path path, long bytes, Instant newestMtime) {}
}
