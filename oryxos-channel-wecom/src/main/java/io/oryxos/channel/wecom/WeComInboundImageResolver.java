package io.oryxos.channel.wecom;

import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.session.ImageMime;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企微入站图片：payload 常为腾讯云 COS 临时 URL。部分 Vision provider 无法直拉该 URL（或判为非法格式），故先下载落盘再交给 enricher /
 * MediaPart（对齐飞书/钉钉本地路径策略）。
 *
 * <p>失败保留原远程 URL（降级，不阻断编排）。
 */
final class WeComInboundImageResolver {

  private static final Logger LOG = LoggerFactory.getLogger(WeComInboundImageResolver.class);

  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String FALLBACK_SEGMENT = "x";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final char PATH_SAFE_REPLACEMENT = '_';
  private static final int MAX_SEGMENT_LEN = 96;
  private static final int DOWNLOAD_ATTEMPTS = 2;
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final String SCHEME_HTTPS = "https";
  private static final String SCHEME_HTTP = "http";
  private static final String HOST_SUFFIX_MYQCLOUD = ".myqcloud.com";
  private static final String HOST_SUFFIX_QCLOUD = ".qcloud.com";
  private static final String HOST_SUFFIX_WEIXIN = ".weixin.qq.com";
  private static final String HOST_WEIXIN = "weixin.qq.com";

  private final HttpClient httpClient;
  private final Path mediaRoot;
  private final String channelName;
  private final boolean trustLoopback;

  WeComInboundImageResolver(Path mediaRoot, String channelName) {
    this(
        HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build(),
        mediaRoot,
        channelName,
        false);
  }

  WeComInboundImageResolver(HttpClient httpClient, Path mediaRoot, String channelName) {
    this(httpClient, mediaRoot, channelName, false);
  }

  /** 单测：允许 127.0.0.1 / localhost 以便本地 HttpServer 验证落盘。 */
  WeComInboundImageResolver(
      HttpClient httpClient, Path mediaRoot, String channelName, boolean trustLoopback) {
    this.httpClient = httpClient;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
    this.trustLoopback = trustLoopback;
  }

  InboundMessage resolve(InboundMessage message) {
    if (message.attachments().isEmpty()) {
      return message;
    }
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
    return InboundAttachment.TYPE_IMAGE.equals(attachment.type())
        && attachment.url() != null
        && ImageMime.isHttpUrl(attachment.url().strip());
  }

  static boolean hasImage(InboundMessage message) {
    for (InboundAttachment attachment : message.attachments()) {
      if (InboundAttachment.TYPE_IMAGE.equals(attachment.type())) {
        return true;
      }
    }
    return false;
  }

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String remoteUrl = attachment.url().strip();
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        Path file = writeToMediaRoot(messageId, remoteUrl);
        return new InboundAttachment(
            InboundAttachment.TYPE_IMAGE, file.toAbsolutePath().toString(), remoteUrl);
      } catch (IOException | InterruptedException | RuntimeException e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        last = e;
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          LOG.warn(
              "企微渠道 {} 下载图片超时重试 {}/{}（messageId={}）：{}",
              sanitize(channelName),
              attempt,
              DOWNLOAD_ATTEMPTS,
              sanitize(messageId),
              sanitize(e.getMessage()));
          continue;
        }
        break;
      }
    }
    LOG.warn(
        "企微渠道 {} 下载图片失败（messageId={}）：{}，保留远程 URL",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private Path writeToMediaRoot(String messageId, String remoteUrl)
      throws IOException, InterruptedException {
    URI uri = URI.create(remoteUrl);
    if (!isAllowedMediaUri(uri)) {
      throw new IllegalStateException("拒绝非企微图床临时下载地址: " + sanitize(uri.getHost()));
    }
    HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(HTTP_TIMEOUT).GET().build();
    HttpResponse<byte[]> response =
        httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < HTTP_STATUS_OK_MIN
        || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException("下载临时文件 HTTP " + response.statusCode());
    }
    byte[] bytes = response.body();
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("下载临时文件为空");
    }
    String ext = extensionOf(uri.getPath());
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    String stem = safeSegment(Integer.toHexString(remoteUrl.hashCode()));
    Path target = dir.resolve(stem + ext);
    Files.write(target, bytes);
    if (DEFAULT_EXTENSION.equals(ext)) {
      String sniffed = ImageMime.probeFile(target);
      String betterExt = ImageMime.extensionFor(sniffed);
      if (!DEFAULT_EXTENSION.equals(betterExt) && !betterExt.equals(ext)) {
        Path renamed = dir.resolve(stem + betterExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("企微图片重命名扩展名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    }
    return target;
  }

  private static final String HOST_LOOPBACK_IP = "127.0.0.1";
  private static final String HOST_LOCALHOST = "localhost";

  private boolean isAllowedMediaUri(URI uri) {
    if (uri == null || uri.getHost() == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = asciiLower(uri.getScheme());
    if (!SCHEME_HTTPS.equals(scheme) && !SCHEME_HTTP.equals(scheme)) {
      return false;
    }
    String host = asciiLower(uri.getHost());
    if (trustLoopback && isLoopbackHost(host)) {
      return true;
    }
    return isAllowedMediaHost(host);
  }

  private static boolean isLoopbackHost(String host) {
    return HOST_LOOPBACK_IP.equals(host) || HOST_LOCALHOST.equals(host);
  }

  static boolean isAllowedMediaHost(String mediaHost) {
    if (mediaHost == null || mediaHost.isBlank()) {
      return false;
    }
    return mediaHost.endsWith(HOST_SUFFIX_MYQCLOUD)
        || mediaHost.endsWith(HOST_SUFFIX_QCLOUD)
        || mediaHost.equals(HOST_WEIXIN)
        || mediaHost.endsWith(HOST_SUFFIX_WEIXIN);
  }

  private static boolean isTransientTimeout(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      String name = t.getClass().getName();
      if (name.contains("Timeout") || name.contains("InterruptedIO")) {
        return true;
      }
      String msg = t.getMessage();
      if (msg != null) {
        String lower = asciiLower(msg);
        if (lower.contains("timeout") || lower.contains("timed out")) {
          return true;
        }
      }
    }
    return false;
  }

  private static String extensionOf(String path) {
    if (path == null || path.isBlank()) {
      return DEFAULT_EXTENSION;
    }
    int slash = path.lastIndexOf('/');
    String fileName = slash >= 0 ? path.substring(slash + 1) : path;
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return DEFAULT_EXTENSION;
    }
    String ext = asciiLower(fileName.substring(dot));
    if (!ext.matches(SAFE_EXTENSION_PATTERN)) {
      return DEFAULT_EXTENSION;
    }
    return ext;
  }

  static String safeSegment(String raw) {
    if (raw == null || raw.isBlank()) {
      return FALLBACK_SEGMENT;
    }
    String cleaned = raw.replaceAll("[^a-zA-Z0-9._-]", String.valueOf(PATH_SAFE_REPLACEMENT));
    if (cleaned.length() > MAX_SEGMENT_LEN) {
      cleaned = cleaned.substring(0, MAX_SEGMENT_LEN);
    }
    if (cleaned.isBlank() || cleaned.chars().allMatch(ch -> ch == PATH_SAFE_REPLACEMENT)) {
      return FALLBACK_SEGMENT;
    }
    return cleaned;
  }

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

  private static String sanitize(String value) {
    return value == null
        ? ""
        : value.replace('\r', PATH_SAFE_REPLACEMENT).replace('\n', PATH_SAFE_REPLACEMENT);
  }
}
