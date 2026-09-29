package io.oryxos.channel.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundMediaPaths;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.LimitedMediaWriter;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 飞书入站图片：官方无公开临时 URL，须用 message_id + image_key 调「获取消息中的资源文件」下载二进制。成功后把本地绝对路径写入 {@link
 * InboundAttachment#url()}，enricher 即可与企微一样输出「图片链接」。
 *
 * <p>下载失败时保留原 {@code image_key} 引用（降级，不阻断编排）。
 */
final class FeishuInboundImageResolver {

  private static final Logger LOG = LoggerFactory.getLogger(FeishuInboundImageResolver.class);

  private static final String RESOURCE_TYPE_IMAGE = "image";
  private static final String RESOURCE_TYPE_FILE = "file";
  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final int DOWNLOAD_ATTEMPTS = 2;

  private final Client client;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;

  FeishuInboundImageResolver(Client client, Path mediaRoot, String channelName) {
    this(client, mediaRoot, channelName, InboundMediaJanitor.fromEnv());
  }

  /** 单测可注入 janitor。 */
  FeishuInboundImageResolver(
      Client client, Path mediaRoot, String channelName, InboundMediaJanitor janitor) {
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
    String fileKey = attachment.reference();
    boolean fileLike =
        InboundAttachment.TYPE_FILE.equals(attachment.type())
            || InboundAttachment.TYPE_AUDIO.equals(attachment.type())
            || InboundAttachment.TYPE_VIDEO.equals(attachment.type());
    String resourceType = fileLike ? RESOURCE_TYPE_FILE : RESOURCE_TYPE_IMAGE;
    String kind =
        InboundAttachment.TYPE_AUDIO.equals(attachment.type())
            ? "语音"
            : (InboundAttachment.TYPE_VIDEO.equals(attachment.type())
                ? "视频"
                : (fileLike ? "文件" : "图片"));
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        GetMessageResourceResp resp =
            client
                .im()
                .messageResource()
                .get(
                    GetMessageResourceReq.newBuilder()
                        .messageId(messageId)
                        .fileKey(fileKey)
                        .type(resourceType)
                        .build());
        if (resp == null || !resp.success() || resp.getData() == null) {
          LOG.warn(
              "飞书渠道 {} 下载{}失败（messageId={}, key={}, code={}, msg={}），保留原引用",
              sanitize(channelName),
              sanitize(kind),
              sanitize(messageId),
              sanitize(fileKey),
              resp == null ? -1 : resp.getCode(),
              sanitize(resp == null ? null : resp.getMsg()));
          return attachment;
        }
        Path path = writeToMediaRoot(messageId, fileKey, resp, fileLike, attachment);
        return new InboundAttachment(
            attachment.type(), path.toAbsolutePath().toString(), fileKey, attachment.fileName());
      } catch (Exception e) {
        last = e;
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          LOG.warn(
              "飞书渠道 {} 下载{}超时重试 {}/{}（messageId={}, key={}）：{}",
              sanitize(channelName),
              sanitize(kind),
              attempt,
              DOWNLOAD_ATTEMPTS,
              sanitize(messageId),
              sanitize(fileKey),
              sanitize(e.getMessage()));
          continue;
        }
        break;
      }
    }
    LOG.warn(
        "飞书渠道 {} 下载{}异常（messageId={}, key={}）：{}，保留原引用",
        sanitize(channelName),
        sanitize(kind),
        sanitize(messageId),
        sanitize(fileKey),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
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

  private Path writeToMediaRoot(
      String messageId,
      String fileKey,
      GetMessageResourceResp resp,
      boolean fileAttachment,
      InboundAttachment attachment)
      throws IOException {
    String fileName = firstNonBlank(resp.getFileName(), attachment.fileName());
    String ext = extensionOf(fileName);
    if (DEFAULT_EXTENSION.equals(ext) && InboundAttachment.TYPE_VIDEO.equals(attachment.type())) {
      ext = ".mp4";
    } else if (DEFAULT_EXTENSION.equals(ext)
        && InboundAttachment.TYPE_AUDIO.equals(attachment.type())) {
      ext = ".ogg";
    }
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    Path target = dir.resolve(safeSegment(fileKey) + ext);
    janitor.ensureQuotaOrThrow(mediaRoot);
    writeLimitedResource(resp.getData(), target);
    // 图片常无后缀：用魔数改扩展名；文件无后缀时嗅探 PDF
    if (!fileAttachment && DEFAULT_EXTENSION.equals(ext)) {
      String sniffed = ImageMime.probeFile(target);
      String betterExt = ImageMime.extensionFor(sniffed);
      if (!DEFAULT_EXTENSION.equals(betterExt) && !betterExt.equals(ext)) {
        Path renamed = dir.resolve(safeSegment(fileKey) + betterExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("飞书图片重命名扩展名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    } else if (fileAttachment) {
      String better = InboundMediaExt.betterFileExtension(target, ext);
      if (better != null) {
        Path renamed = dir.resolve(safeSegment(fileKey) + better);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("飞书文件 PDF 扩展名重命名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    }
    return target;
  }

  /**
   * SDK {@code getData()} 为 {@link ByteArrayOutputStream}；经 {@link LimitedMediaWriter#copyLimited}
   * 限长落盘。
   */
  private static void writeLimitedResource(ByteArrayOutputStream data, Path target)
      throws IOException {
    if (data == null) {
      throw new IOException("下载临时文件为空");
    }
    if (data.size() > InboundMediaLimits.MAX_FILE_BYTES) {
      throw new IOException("入站文件超过上限 " + InboundMediaLimits.MAX_FILE_BYTES + " 字节");
    }
    try (InputStream in = new ByteArrayInputStream(data.toByteArray())) {
      LimitedMediaWriter.copyLimited(in, target, InboundMediaLimits.MAX_FILE_BYTES);
    }
  }

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

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    if (b != null && !b.isBlank()) {
      return b;
    }
    return null;
  }

  static String safeSegment(String raw) {
    return InboundMediaPaths.safeSegment(raw);
  }

  private static String sanitize(String value) {
    // 内联替换：SpotBugs CRLF_INJECTION_LOGS 需在本类内可见的 \r/\n 清洗
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
