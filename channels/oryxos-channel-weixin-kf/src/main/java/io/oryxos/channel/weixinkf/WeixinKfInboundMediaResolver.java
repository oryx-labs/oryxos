package io.oryxos.channel.weixinkf;

import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundMediaPaths;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.LimitedMediaWriter;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微信客服入站媒体：{@code media_id} → {@code /cgi-bin/media/get} 落盘，写入 {@link InboundAttachment#url()}。
 *
 * <p>失败保留原 reference（降级，不阻断编排）。
 */
final class WeixinKfInboundMediaResolver {

  private static final Logger LOG = LoggerFactory.getLogger(WeixinKfInboundMediaResolver.class);

  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String EXT_JPG = ".jpg";
  private static final String EXT_PNG = ".png";
  private static final String EXT_GIF = ".gif";
  private static final String EXT_WEBP = ".webp";
  private static final String EXT_PDF = ".pdf";
  private static final String EXT_MP4 = ".mp4";
  private static final String EXT_AMR = ".amr";
  private static final String CT_JPEG = "jpeg";
  private static final String CT_JPG = "jpg";
  private static final String CT_PNG = "png";
  private static final String CT_GIF = "gif";
  private static final String CT_WEBP = "webp";
  private static final String CT_PDF = "pdf";
  private static final String CT_MP4 = "mp4";
  private static final String CT_SILK = "silk";
  private static final String CT_OCTET = "octet-stream";
  private static final String CT_AMR = "amr";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final int DOWNLOAD_ATTEMPTS = 2;

  private final WeixinKfClient client;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;

  WeixinKfInboundMediaResolver(WeixinKfClient client, Path mediaRoot, String channelName) {
    this(client, mediaRoot, channelName, InboundMediaJanitor.fromEnv());
  }

  WeixinKfInboundMediaResolver(
      WeixinKfClient client, Path mediaRoot, String channelName, InboundMediaJanitor janitor) {
    this.client = client;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
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

  private static boolean needsDownload(InboundAttachment attachment) {
    if (attachment.reference() == null || attachment.reference().isBlank()) {
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

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String mediaId = attachment.reference();
    String kind = kindLabel(attachment.type());
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        WeixinKfMediaBlob blob = client.downloadMedia(mediaId);
        Path path = writeToMediaRoot(messageId, mediaId, blob, attachment);
        return new InboundAttachment(
            attachment.type(),
            path.toAbsolutePath().toString(),
            mediaId,
            firstNonBlank(attachment.fileName(), blob.fileName()));
      } catch (Exception e) {
        last = e;
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          LOG.warn(
              "微信客服渠道 {} 下载{}超时重试 {}/{}（messageId={}, mediaId={}）：{}",
              sanitize(channelName),
              sanitize(kind),
              attempt,
              DOWNLOAD_ATTEMPTS,
              sanitize(messageId),
              sanitize(mediaId),
              sanitize(e.getMessage()));
          continue;
        }
        break;
      }
    }
    LOG.warn(
        "微信客服渠道 {} 下载{}失败（messageId={}, mediaId={}）：{}，保留原引用",
        sanitize(channelName),
        sanitize(kind),
        sanitize(messageId),
        sanitize(mediaId),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private Path writeToMediaRoot(
      String messageId, String mediaId, WeixinKfMediaBlob blob, InboundAttachment attachment)
      throws IOException {
    String fileName = firstNonBlank(attachment.fileName(), blob.fileName());
    String ext = extensionOf(fileName);
    if (DEFAULT_EXTENSION.equals(ext)) {
      ext = defaultExtForType(attachment.type(), blob.contentType());
    }
    Path dir = mediaRoot.resolve(InboundMediaPaths.safeSegment(messageId));
    Files.createDirectories(dir);
    Path target = dir.resolve(InboundMediaPaths.safeSegment(mediaId) + ext);
    janitor.ensureQuotaOrThrow(mediaRoot);
    LimitedMediaWriter.writeLimited(blob.bytes(), target, InboundMediaLimits.MAX_FILE_BYTES);
    boolean fileLike =
        InboundAttachment.TYPE_FILE.equals(attachment.type())
            || InboundAttachment.TYPE_AUDIO.equals(attachment.type())
            || InboundAttachment.TYPE_VIDEO.equals(attachment.type());
    if (!fileLike && DEFAULT_EXTENSION.equals(ext)) {
      String sniffed = ImageMime.probeFile(target);
      String betterExt = ImageMime.extensionFor(sniffed);
      if (!DEFAULT_EXTENSION.equals(betterExt) && !betterExt.equals(ext)) {
        Path renamed = dir.resolve(InboundMediaPaths.safeSegment(mediaId) + betterExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("微信客服图片重命名扩展名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    } else if (fileLike) {
      String better = InboundMediaExt.betterFileExtension(target, ext);
      if (better != null) {
        Path renamed = dir.resolve(InboundMediaPaths.safeSegment(mediaId) + better);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("微信客服文件扩展名重命名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    }
    return target;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII MIME 子串做 Locale.ROOT 小写匹配以选扩展名，不参与安全/身份比较")
  private static String defaultExtForType(String type, String contentType) {
    if (contentType != null) {
      String ct = contentType.toLowerCase(Locale.ROOT);
      if (ct.contains(CT_JPEG) || ct.contains(CT_JPG)) {
        return EXT_JPG;
      }
      if (ct.contains(CT_PNG)) {
        return EXT_PNG;
      }
      if (ct.contains(CT_GIF)) {
        return EXT_GIF;
      }
      if (ct.contains(CT_WEBP)) {
        return EXT_WEBP;
      }
      if (ct.contains(CT_PDF)) {
        return EXT_PDF;
      }
      if (ct.contains(CT_MP4)) {
        return EXT_MP4;
      }
      if (ct.contains(CT_SILK) || ct.contains(CT_OCTET)) {
        if (InboundAttachment.TYPE_AUDIO.equals(type)) {
          // sync_msg 默认 voice_format=AMR；octet-stream 优先按 AMR，魔数可再纠正为 Silk
          return EXT_AMR;
        }
      }
      if (ct.contains(CT_AMR)) {
        return EXT_AMR;
      }
    }
    if (InboundAttachment.TYPE_IMAGE.equals(type)) {
      return DEFAULT_EXTENSION;
    }
    if (InboundAttachment.TYPE_AUDIO.equals(type)) {
      return EXT_AMR;
    }
    if (InboundAttachment.TYPE_VIDEO.equals(type)) {
      return EXT_MP4;
    }
    return DEFAULT_EXTENSION;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 扩展名做 Locale.ROOT 小写与正则匹配")
  private static String extensionOf(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      return DEFAULT_EXTENSION;
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return DEFAULT_EXTENSION;
    }
    String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
    if (!ext.matches(SAFE_EXTENSION_PATTERN)) {
      return DEFAULT_EXTENSION;
    }
    return ext;
  }

  private static String kindLabel(String type) {
    if (InboundAttachment.TYPE_AUDIO.equals(type)) {
      return "语音";
    }
    if (InboundAttachment.TYPE_VIDEO.equals(type)) {
      return "视频";
    }
    if (InboundAttachment.TYPE_FILE.equals(type)) {
      return "文件";
    }
    return "图片";
  }

  private static boolean isTransientTimeout(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      String name = t.getClass().getName();
      if (name.contains("Timeout") || name.contains("InterruptedIO")) {
        return true;
      }
      String msg = t.getMessage();
      if (msg != null) {
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("timeout") || lower.contains("timed out")) {
          return true;
        }
      }
    }
    return false;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
  }

  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
