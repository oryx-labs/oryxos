package io.oryxos.channel.slack;

import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaHttp;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundMediaPaths;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.LimitedMediaWriter;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Slack 入站图片/文件：事件里的 {@code url_private(_download)} 需带 Bot Token 下载后落盘，再交给 enricher / Vision。
 *
 * <p>失败保留原远程 URL（降级，不阻断编排）。
 */
final class SlackInboundMediaResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SlackInboundMediaResolver.class);

  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String EXT_DOT = ".";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final int DOWNLOAD_ATTEMPTS = 2;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
  private static final String HEADER_AUTHORIZATION = "Authorization";
  private static final String SCHEME_HTTPS = "https";
  private static final String HOST_SLACK = "slack.com";
  private static final String HOST_SUFFIX_SLACK = ".slack.com";

  private final String botToken;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;

  SlackInboundMediaResolver(String botToken, Path mediaRoot, String channelName) {
    this(botToken, mediaRoot, channelName, InboundMediaJanitor.fromEnv());
  }

  SlackInboundMediaResolver(
      String botToken, Path mediaRoot, String channelName, InboundMediaJanitor janitor) {
    this.botToken = botToken;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
    this.janitor = janitor == null ? InboundMediaJanitor.fromEnv() : janitor;
  }

  InboundMessage resolve(InboundMessage message) {
    if (message == null || message.attachments().isEmpty()) {
      return message;
    }
    janitor.sweepIfDue(mediaRoot);
    List<InboundAttachment> resolved = new ArrayList<>(message.attachments().size());
    boolean changed = false;
    for (InboundAttachment attachment : message.attachments()) {
      if (!needsDownload(attachment)) {
        resolved.add(attachment);
        continue;
      }
      InboundAttachment next = downloadOrKeep(message.messageId(), attachment);
      changed |= next != attachment;
      resolved.add(next);
    }
    if (!changed) {
      return message;
    }
    return new InboundMessage(
        message.channelType(),
        message.channelName(),
        message.messageId(),
        message.chatKind(),
        message.userId(),
        message.chatId(),
        message.content(),
        message.textual(),
        message.mentionedBot(),
        resolved);
  }

  static boolean needsDownload(InboundAttachment attachment) {
    if (attachment == null || attachment.url() == null || attachment.url().isBlank()) {
      return false;
    }
    String type = attachment.type();
    return InboundAttachment.TYPE_IMAGE.equals(type) || InboundAttachment.TYPE_FILE.equals(type);
  }

  static boolean hasDownloadableMedia(InboundMessage message) {
    if (message == null) {
      return false;
    }
    for (InboundAttachment attachment : message.attachments()) {
      if (needsDownload(attachment)) {
        return true;
      }
    }
    return false;
  }

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String remoteUrl = attachment.url().strip();
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      long started = System.nanoTime();
      try {
        Path path = writeToMediaRoot(messageId, remoteUrl, attachment);
        LOG.info(
            "Slack 渠道 {} 媒体已落盘（messageId={}, type={}, {}ms）",
            sanitize(channelName),
            sanitize(messageId),
            sanitize(attachment.type()),
            (System.nanoTime() - started) / 1_000_000L);
        return new InboundAttachment(
            attachment.type(), path.toAbsolutePath().toString(), remoteUrl, attachment.fileName());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        last = e;
        LOG.warn(
            "Slack 渠道 {} 下载媒体失败尝试 {}/{}（messageId={}, host={}, {}ms）：{}",
            sanitize(channelName),
            attempt,
            DOWNLOAD_ATTEMPTS,
            sanitize(messageId),
            sanitize(hostOf(remoteUrl)),
            (System.nanoTime() - started) / 1_000_000L,
            sanitize(e.getMessage()));
      }
    }
    LOG.warn(
        "Slack 渠道 {} 下载媒体最终失败（messageId={}）：{}，保留远程 URL",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private Path writeToMediaRoot(String messageId, String remoteUrl, InboundAttachment attachment)
      throws Exception {
    URI uri = URI.create(remoteUrl);
    if (!isAllowedMediaUri(uri)) {
      throw new IllegalStateException("拒绝非 Slack 文件域临时下载地址: " + sanitize(uri.getHost()));
    }
    Map<String, String> headers = Map.of(HEADER_AUTHORIZATION, "Bearer " + botToken);
    byte[] bytes =
        InboundMediaHttp.getBytesFollowingAllowlist(
            uri,
            CONNECT_TIMEOUT,
            READ_TIMEOUT,
            InboundMediaLimits.MAX_FILE_BYTES,
            this::isAllowedMediaUri,
            headers);
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("下载临时文件为空");
    }
    String ext = extensionFor(attachment, remoteUrl);
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    String stem = "slack-media";
    Path target = dir.resolve(stem + ext);
    janitor.ensureQuotaOrThrow(mediaRoot);
    LimitedMediaWriter.writeLimited(bytes, target, InboundMediaLimits.MAX_FILE_BYTES);
    if (InboundAttachment.TYPE_IMAGE.equals(attachment.type())
        && DEFAULT_EXTENSION.equals(ext)
        && ImageMime.hasRecognizedMagic(target)) {
      String betterExt = ImageMime.extensionFor(ImageMime.probeFile(target));
      if (betterExt != null && !DEFAULT_EXTENSION.equals(betterExt) && !betterExt.equals(ext)) {
        Path renamed = dir.resolve(stem + betterExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (Exception ignored) {
          // 保留原扩展名
        }
      }
    }
    String better = InboundMediaExt.betterFileExtension(target, ext);
    if (better != null && !better.equals(ext)) {
      Path renamed = dir.resolve(stem + better);
      try {
        Files.move(target, renamed);
        return renamed;
      } catch (Exception ignored) {
        // 保留原扩展名
      }
    }
    return target;
  }

  private static String extensionFor(InboundAttachment attachment, String remoteUrl) {
    if (attachment.fileName() != null && attachment.fileName().contains(EXT_DOT)) {
      String fromName = extensionOf(attachment.fileName());
      if (fromName != null) {
        return fromName;
      }
    }
    String fromUrl = extensionOf(remoteUrl);
    return fromUrl == null ? DEFAULT_EXTENSION : fromUrl;
  }

  private static String extensionOf(String nameOrUrl) {
    if (nameOrUrl == null || nameOrUrl.isBlank()) {
      return null;
    }
    String path = nameOrUrl;
    int q = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    int slash = path.lastIndexOf('/');
    if (slash >= 0) {
      path = path.substring(slash + 1);
    }
    int dot = path.lastIndexOf(EXT_DOT);
    if (dot < 0 || dot == path.length() - 1) {
      return null;
    }
    String ext = asciiLower(path.substring(dot));
    if (!ext.matches(SAFE_EXTENSION_PATTERN)) {
      return null;
    }
    return ext;
  }

  private boolean isAllowedMediaUri(URI uri) {
    if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
      return false;
    }
    String scheme = asciiLower(uri.getScheme());
    if (!SCHEME_HTTPS.equals(scheme)) {
      return false;
    }
    String host = asciiLower(uri.getHost());
    return HOST_SLACK.equals(host) || host.endsWith(HOST_SUFFIX_SLACK);
  }

  /** ASCII-only 小写，避免 SpotBugs IMPROPER_UNICODE（scheme/host/ext 均为 ASCII）。 */
  private static String asciiLower(String value) {
    char[] chars = value.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (c >= 'A' && c <= 'Z') {
        chars[i] = (char) (c + ('a' - 'A'));
      }
    }
    return new String(chars);
  }

  private static String safeSegment(String messageId) {
    return InboundMediaPaths.safeSegment(messageId);
  }

  private static String hostOf(String url) {
    try {
      String host = URI.create(url).getHost();
      return host == null ? "" : host;
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
