package io.oryxos.channel.telegram;

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
 * Telegram 入站图片/文件/语音/视频：{@code file_id} 经 {@code getFile} 换到下载地址后就地落盘，再交给 enricher / Vision /
 * Whisper。
 *
 * <p><b>为什么不直接把下载地址交出去</b>：Telegram 的文件下载地址形如 {@code
 * https://api.telegram.org/file/bot<token>/<path>} —— bot token 就在 URL 路径里。该 URL 一旦进入 Agent 输入，就会随
 * prompt 发给 LLM 厂商、落进 {@code sessions.messages_json}，并在图片情形下作为远程图链
 * 交给厂商去取件。其余渠道（飞书/企微/钉钉/Discord/Slack/QQ）都是先落盘再交出本地路径，这里对齐同一做法： 交给核心的 {@code url} 是本机路径，{@code
 * reference} 保持原始 {@code file_id}（不含凭证）。
 *
 * <p>失败保留 file_id（不带 token），降级不阻断编排。
 */
final class TelegramInboundMediaResolver {

  private static final Logger LOG = LoggerFactory.getLogger(TelegramInboundMediaResolver.class);

  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String EXT_DOT = ".";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final int DOWNLOAD_ATTEMPTS = 2;
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
  private static final String MEDIA_STEM = "telegram-media";

  private final String apiBase;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;

  TelegramInboundMediaResolver(String apiBase, Path mediaRoot, String channelName) {
    this(apiBase, mediaRoot, channelName, InboundMediaJanitor.fromEnv());
  }

  TelegramInboundMediaResolver(
      String apiBase, Path mediaRoot, String channelName, InboundMediaJanitor janitor) {
    this.apiBase = apiBase;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
    this.janitor = janitor == null ? InboundMediaJanitor.fromEnv() : janitor;
  }

  /**
   * 把带 {@code file_id} 的附件落盘，换成本地路径。
   *
   * @param message 归一化入站消息
   * @param fileUrlResolver {@code file_id → 下载地址}（内部会调 Telegram {@code getFile}）
   */
  InboundMessage resolve(
      InboundMessage message, java.util.function.Function<String, String> fileUrlResolver) {
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
      InboundAttachment next = downloadOrKeep(message.messageId(), attachment, fileUrlResolver);
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

  /** 只有「还没有 url、但有 file_id」的媒体需要落盘。 */
  static boolean needsDownload(InboundAttachment attachment) {
    if (attachment == null || attachment.reference() == null || attachment.reference().isBlank()) {
      return false;
    }
    if (attachment.url() != null && !attachment.url().isBlank()) {
      return false;
    }
    String type = attachment.type();
    return InboundAttachment.TYPE_IMAGE.equals(type)
        || InboundAttachment.TYPE_FILE.equals(type)
        || InboundAttachment.TYPE_AUDIO.equals(type)
        || InboundAttachment.TYPE_VIDEO.equals(type);
  }

  private InboundAttachment downloadOrKeep(
      String messageId,
      InboundAttachment attachment,
      java.util.function.Function<String, String> fileUrlResolver) {
    String fileId = attachment.reference().strip();
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      long started = System.nanoTime();
      try {
        // getFile：把 file_id 换成下载地址。★ 这个地址含 token，只在本进程内用于下载，不交给核心。
        String downloadUrl = fileUrlResolver.apply(fileId);
        if (downloadUrl == null || downloadUrl.isBlank()) {
          throw new IllegalStateException("getFile 未返回下载地址");
        }
        Path path = writeToMediaRoot(messageId, downloadUrl, attachment);
        LOG.info(
            "Telegram 渠道 {} 媒体已落盘（messageId={}, type={}, {}ms）",
            sanitize(channelName),
            sanitize(messageId),
            sanitize(attachment.type()),
            (System.nanoTime() - started) / 1_000_000L);
        // url = 本机路径；reference 保持原始 file_id（不含凭证），便于排障与重试
        return new InboundAttachment(
            attachment.type(), path.toAbsolutePath().toString(), fileId, attachment.fileName());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        last = e;
        LOG.warn(
            "Telegram 渠道 {} 下载媒体失败尝试 {}/{}（messageId={}, {}ms）：{}",
            sanitize(channelName),
            attempt,
            DOWNLOAD_ATTEMPTS,
            sanitize(messageId),
            (System.nanoTime() - started) / 1_000_000L,
            sanitize(e.getMessage()));
      }
    }
    LOG.warn(
        "Telegram 渠道 {} 下载媒体最终失败（messageId={}）：{}，保留 file_id",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private Path writeToMediaRoot(String messageId, String downloadUrl, InboundAttachment attachment)
      throws Exception {
    URI uri = URI.create(downloadUrl);
    if (!isAllowedDownloadUri(uri)) {
      // Telegram 的下载地址必须落在配置的 apiBase 主机上；其余一律拒绝
      throw new IllegalStateException("拒绝非 Telegram 主机的下载地址: " + sanitize(uri.getHost()));
    }
    byte[] bytes =
        InboundMediaHttp.getBytesFollowingAllowlist(
            uri,
            CONNECT_TIMEOUT,
            READ_TIMEOUT,
            InboundMediaLimits.MAX_FILE_BYTES,
            this::isAllowedDownloadUri,
            Map.of());
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("下载临时文件为空");
    }
    String ext = extensionFor(attachment, uri.getPath());
    Path dir = mediaRoot.resolve(InboundMediaPaths.safeSegment(messageId));
    Files.createDirectories(dir);
    Path target = dir.resolve(MEDIA_STEM + ext);
    janitor.ensureQuotaOrThrow(mediaRoot);
    LimitedMediaWriter.writeLimited(bytes, target, InboundMediaLimits.MAX_FILE_BYTES);
    if (InboundAttachment.TYPE_IMAGE.equals(attachment.type())
        && DEFAULT_EXTENSION.equals(ext)
        && ImageMime.hasRecognizedMagic(target)) {
      String betterExt = ImageMime.extensionFor(ImageMime.probeFile(target));
      if (betterExt != null && !DEFAULT_EXTENSION.equals(betterExt) && !betterExt.equals(ext)) {
        Path renamed = dir.resolve(MEDIA_STEM + betterExt);
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
      Path renamed = dir.resolve(MEDIA_STEM + better);
      try {
        Files.move(target, renamed);
        return renamed;
      } catch (Exception ignored) {
        // 保留原扩展名
      }
    }
    return target;
  }

  /**
   * 只允许配置的 Telegram API 主机上的地址（scheme 也要一致）；重定向的每一跳都要过这一关。
   *
   * <p>比较 scheme 而不是写死 https：生产 apiBase 是 {@code https://api.telegram.org}，本地部署或测试 可能是别的基址 ——
   * 判据是「这个地址确实在配置的 API 主机上」，而不是「它看起来像 https」。
   */
  boolean isAllowedDownloadUri(URI uri) {
    if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
      return false;
    }
    URI base = URI.create(apiBase);
    String baseHost = base.getHost();
    if (baseHost == null || baseHost.isBlank() || base.getScheme() == null) {
      return false;
    }
    return asciiEqualsIgnoreCase(baseHost, uri.getHost())
        && asciiEqualsIgnoreCase(base.getScheme(), uri.getScheme());
  }

  /** 仅 ASCII 大小写折叠，避免 {@code equalsIgnoreCase} 触发 SpotBugs 的 Unicode 变换告警。 */
  private static boolean asciiEqualsIgnoreCase(String left, String right) {
    if (left == null || right == null || left.length() != right.length()) {
      return false;
    }
    for (int i = 0; i < left.length(); i++) {
      char a = left.charAt(i);
      char b = right.charAt(i);
      if (a >= 'A' && a <= 'Z') {
        a += 'a' - 'A';
      }
      if (b >= 'A' && b <= 'Z') {
        b += 'a' - 'A';
      }
      if (a != b) {
        return false;
      }
    }
    return true;
  }

  private static String extensionFor(InboundAttachment attachment, String urlPath) {
    if (attachment.fileName() != null && attachment.fileName().contains(EXT_DOT)) {
      String fromName = extensionOf(attachment.fileName());
      if (fromName != null) {
        return fromName;
      }
    }
    if (urlPath != null && urlPath.contains(EXT_DOT)) {
      String fromUrl = extensionOf(urlPath);
      if (fromUrl != null) {
        return fromUrl;
      }
    }
    return DEFAULT_EXTENSION;
  }

  private static String extensionOf(String value) {
    int dot = value.lastIndexOf('.');
    if (dot < 0 || dot == value.length() - 1) {
      return null;
    }
    String candidate = value.substring(dot).toLowerCase(java.util.Locale.ROOT);
    return candidate.matches(SAFE_EXTENSION_PATTERN) ? candidate : null;
  }

  private static String sanitize(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
