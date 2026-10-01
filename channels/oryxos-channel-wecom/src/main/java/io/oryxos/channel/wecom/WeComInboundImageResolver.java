package io.oryxos.channel.wecom;

import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaHttp;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundMediaPaths;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.LimitedMediaWriter;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
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
 * <p>失败保留原远程 URL（降级，不阻断编排）。下载走 {@link InboundMediaHttp#getBytesFollowingAllowlist}（硬 connect/read
 * 超时），避免 JDK HttpClient 在 COS 链路上拖死 WS 回调线程。
 */
final class WeComInboundImageResolver {

  private static final Logger LOG = LoggerFactory.getLogger(WeComInboundImageResolver.class);

  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String EXT_MP4 = ".mp4";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final int DOWNLOAD_ATTEMPTS = 2;

  /** COS 临时链约 5 分钟有效；大视频需足够读超时（曾见 ~90s 读超时后重试又撞 50MB 上限）。 */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

  private static final String SCHEME_HTTPS = "https";
  private static final String SCHEME_HTTP = "http";
  private static final String HOST_SUFFIX_MYQCLOUD = ".myqcloud.com";
  private static final String HOST_SUFFIX_QCLOUD = ".qcloud.com";
  private static final String HOST_SUFFIX_WEIXIN = ".weixin.qq.com";
  private static final String HOST_WEIXIN = "weixin.qq.com";

  private final Path mediaRoot;
  private final String channelName;
  private final boolean trustLoopback;
  private final InboundMediaJanitor janitor;

  WeComInboundImageResolver(Path mediaRoot, String channelName) {
    this(mediaRoot, channelName, false, InboundMediaJanitor.fromEnv());
  }

  /** 单测兼容旧构造（HttpClient 忽略，改走 UrlConnection）。 */
  WeComInboundImageResolver(HttpClient httpClient, Path mediaRoot, String channelName) {
    this(mediaRoot, channelName, false, InboundMediaJanitor.fromEnv());
  }

  /** 单测：允许 127.0.0.1 / localhost 以便本地 HttpServer 验证落盘。 */
  WeComInboundImageResolver(
      HttpClient httpClient, Path mediaRoot, String channelName, boolean trustLoopback) {
    this(mediaRoot, channelName, trustLoopback, InboundMediaJanitor.fromEnv());
  }

  /** 单测可注入 janitor。 */
  WeComInboundImageResolver(
      HttpClient httpClient,
      Path mediaRoot,
      String channelName,
      boolean trustLoopback,
      InboundMediaJanitor janitor) {
    this(mediaRoot, channelName, trustLoopback, janitor);
  }

  private WeComInboundImageResolver(
      Path mediaRoot, String channelName, boolean trustLoopback, InboundMediaJanitor janitor) {
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
    this.trustLoopback = trustLoopback;
    this.janitor = janitor == null ? InboundMediaJanitor.fromEnv() : janitor;
  }

  InboundMessage resolve(InboundMessage message) {
    janitor.sweepIfDue(mediaRoot);
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
    if (attachment.url() == null || !ImageMime.isHttpUrl(attachment.url().strip())) {
      return false;
    }
    String type = attachment.type();
    return InboundAttachment.TYPE_IMAGE.equals(type)
        || InboundAttachment.TYPE_FILE.equals(type)
        || InboundAttachment.TYPE_AUDIO.equals(type)
        || InboundAttachment.TYPE_VIDEO.equals(type);
  }

  static boolean hasImage(InboundMessage message) {
    return hasDownloadableMedia(message);
  }

  static boolean hasDownloadableMedia(InboundMessage message) {
    for (InboundAttachment attachment : message.attachments()) {
      String type = attachment.type();
      if (InboundAttachment.TYPE_IMAGE.equals(type)
          || InboundAttachment.TYPE_FILE.equals(type)
          || InboundAttachment.TYPE_AUDIO.equals(type)
          || InboundAttachment.TYPE_VIDEO.equals(type)) {
        return true;
      }
    }
    return false;
  }

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String remoteUrl = attachment.url().strip();
    String aesKey = mediaAesKey(attachment);
    boolean fileLike =
        InboundAttachment.TYPE_FILE.equals(attachment.type())
            || InboundAttachment.TYPE_AUDIO.equals(attachment.type())
            || InboundAttachment.TYPE_VIDEO.equals(attachment.type());
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      long started = System.nanoTime();
      try {
        Path path = writeToMediaRoot(messageId, remoteUrl, aesKey, fileLike, attachment.type());
        LOG.info(
            "企微渠道 {} 媒体已落盘（messageId={}, type={}, {}ms）",
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
            "企微渠道 {} 下载媒体失败尝试 {}/{}（messageId={}, host={}, {}ms）：{}",
            sanitize(channelName),
            attempt,
            DOWNLOAD_ATTEMPTS,
            sanitize(messageId),
            sanitize(hostOf(remoteUrl)),
            (System.nanoTime() - started) / 1_000_000L,
            sanitize(e.getMessage()));
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          continue;
        }
        break;
      }
    }
    LOG.warn(
        "企微渠道 {} 下载媒体最终失败（messageId={}）：{}，保留远程 URL",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  /** normalizer 把 aeskey 放在 reference；已是 http(s) 的 reference 视为旧远程 URL，不是密钥。 */
  private static String mediaAesKey(InboundAttachment attachment) {
    String ref = attachment.reference();
    if (ref == null || ref.isBlank() || ImageMime.isHttpUrl(ref.strip())) {
      return null;
    }
    return ref.strip();
  }

  private Path writeToMediaRoot(
      String messageId, String remoteUrl, String aesKey, boolean file, String attachmentType)
      throws Exception {
    URI uri = URI.create(remoteUrl);
    if (!isAllowedMediaUri(uri)) {
      throw new IllegalStateException("拒绝非企微图床临时下载地址: " + sanitize(uri.getHost()));
    }
    byte[] bytes =
        InboundMediaHttp.getBytesFollowingAllowlist(
            uri,
            CONNECT_TIMEOUT,
            READ_TIMEOUT,
            InboundMediaLimits.MAX_FILE_BYTES,
            this::isAllowedMediaUri);
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("下载临时文件为空");
    }
    if (aesKey != null) {
      bytes = WeComMediaAesDecrypt.decrypt(bytes, aesKey);
      if (bytes.length > InboundMediaLimits.MAX_FILE_BYTES) {
        throw new IllegalStateException("入站文件超过上限 " + InboundMediaLimits.MAX_FILE_BYTES + " 字节");
      }
    } else if (!file && !ImageMime.hasRecognizedMagic(bytes)) {
      throw new IllegalStateException("下载内容非明文图片且消息缺 aeskey，无法解密");
    }
    String ext = extensionOf(uri.getPath());
    if (DEFAULT_EXTENSION.equals(ext) && InboundAttachment.TYPE_VIDEO.equals(attachmentType)) {
      ext = EXT_MP4;
    }
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    String stem = safeSegment(Integer.toHexString(remoteUrl.hashCode()));
    Path target = dir.resolve(stem + ext);
    janitor.ensureQuotaOrThrow(mediaRoot);
    LimitedMediaWriter.writeLimited(bytes, target, InboundMediaLimits.MAX_FILE_BYTES);
    if (!file) {
      if (!ImageMime.hasRecognizedMagic(target)) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException ignored) {
          // 尽力删除坏文件
        }
        throw new IllegalStateException("解密/下载后仍非可识别图片格式");
      }
      if (DEFAULT_EXTENSION.equals(ext)) {
        String betterExt = ImageMime.extensionFor(ImageMime.probeFile(target));
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
    } else {
      String better = InboundMediaExt.betterFileExtension(target, ext);
      if (better == null
          && InboundAttachment.TYPE_VIDEO.equals(attachmentType)
          && InboundMediaExt.isMp4Magic(target)
          && !EXT_MP4.equals(ext)) {
        better = EXT_MP4;
      }
      if (better != null) {
        Path renamed = dir.resolve(stem + better);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("企微文件扩展名重命名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
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
        || HOST_WEIXIN.equals(mediaHost)
        || mediaHost.endsWith(HOST_SUFFIX_WEIXIN);
  }

  private static boolean isTransientTimeout(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      String name = t.getClass().getName();
      if (name.contains("Timeout")
          || name.contains("InterruptedIO")
          || name.contains("SocketTimeout")) {
        return true;
      }
      String msg = t.getMessage();
      if (msg != null) {
        String lower = asciiLower(msg);
        if (lower.contains("timeout")
            || lower.contains("timed out")
            || lower.contains("read timed out")
            || lower.contains("connect timed out")) {
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
    return InboundMediaPaths.safeSegment(raw);
  }

  private static String hostOf(String url) {
    try {
      URI uri = URI.create(url);
      return uri.getHost() == null ? "" : uri.getHost();
    } catch (IllegalArgumentException | IllegalStateException e) {
      return "";
    }
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
    // 内联替换：SpotBugs CRLF_INJECTION_LOGS 需在本类内可见的 \r/\n 清洗
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
