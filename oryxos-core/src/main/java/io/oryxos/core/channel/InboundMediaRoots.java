package io.oryxos.core.channel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入站媒体落盘根目录：优先 {@code user.dir/.oryxos/inbound-media/{channel}}（通常在 FILE 沙箱 {@code .oryxos}
 * 白名单内），失败再退到临时目录。
 */
public final class InboundMediaRoots {

  private static final Logger LOG = LoggerFactory.getLogger(InboundMediaRoots.class);
  private static final String INBOUND_MEDIA = "inbound-media";
  private static final String FALLBACK_TEMP_PREFIX = "oryxos-inbound-media-";

  private InboundMediaRoots() {}

  public static Path forChannel(String channelName, String tempPrefix) {
    String channelSeg = safeSegment(channelName);
    Path preferred = Path.of(System.getProperty("user.dir"), ".oryxos", INBOUND_MEDIA, channelSeg);
    try {
      Files.createDirectories(preferred);
      return preferred;
    } catch (IOException e) {
      LOG.warn(
          "创建入站媒体目录失败（{}），回退临时目录: {}", sanitize(preferred.toString()), sanitize(e.getMessage()));
      try {
        // 前缀用常量：避免 SpotBugs PATH_TRAVERSAL_IN（调用方传入的 tempPrefix 仅作末级目录名）
        Path root = Files.createTempDirectory(FALLBACK_TEMP_PREFIX);
        Path channelDir = root.resolve(channelSeg);
        Files.createDirectories(channelDir);
        return channelDir;
      } catch (IOException fallback) {
        Path last =
            Path.of(
                System.getProperty("java.io.tmpdir"),
                safeSegment(
                    tempPrefix == null || tempPrefix.isBlank() ? FALLBACK_TEMP_PREFIX : tempPrefix),
                channelSeg);
        try {
          Files.createDirectories(last);
        } catch (IOException ignored) {
          // 调用方下载时再失败
        }
        return last;
      }
    }
  }

  static String safeSegment(String raw) {
    return InboundMediaPaths.safeSegment(raw);
  }

  private static String sanitize(String value) {
    // 内联替换：SpotBugs CRLF_INJECTION_LOGS 需在本类内可见的 \r/\n 清洗
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
