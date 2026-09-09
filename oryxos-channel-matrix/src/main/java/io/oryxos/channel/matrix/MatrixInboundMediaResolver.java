package io.oryxos.channel.matrix;

import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundMediaPaths;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.LimitedMediaWriter;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Matrix 入站图/文件：事件里已有 {@code mxc://}，用 Bot token 走鉴权媒体接口落盘。失败保留 mxc，不阻断文字编排。 */
final class MatrixInboundMediaResolver {

  private static final Logger LOG = LoggerFactory.getLogger(MatrixInboundMediaResolver.class);
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX_EXCLUSIVE = 300;
  private static final String MXC_PREFIX = "mxc://";
  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String FILE_PREFIX = "mx-media";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String homeserver;
  private final String token;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;

  MatrixInboundMediaResolver(
      OutboundGuard guard, String homeserver, String token, Path mediaRoot, String channelName) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
        guard,
        homeserver,
        token,
        mediaRoot,
        channelName,
        InboundMediaJanitor.fromEnv());
  }

  MatrixInboundMediaResolver(
      HttpClient http,
      OutboundGuard guard,
      String homeserver,
      String token,
      Path mediaRoot,
      String channelName,
      InboundMediaJanitor janitor) {
    this.http = http;
    this.guard = guard;
    this.homeserver = MatrixMessageSender.trimSlash(homeserver);
    this.token = token;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
    this.janitor = janitor == null ? InboundMediaJanitor.fromEnv() : janitor;
  }

  InboundMessage download(InboundMessage message) {
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
        message.textual() || !message.content().isBlank(),
        message.mentionedBot(),
        resolved);
  }

  static boolean needsDownload(InboundMessage message) {
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

  private static boolean needsDownload(InboundAttachment attachment) {
    return attachment != null
        && attachment.reference() != null
        && !attachment.reference().isBlank()
        && (attachment.url() == null || attachment.url().isBlank());
  }

  static String[] downloadUrls(String homeserver, String mxc) {
    Mxc parsed = parseMxc(mxc);
    if (parsed == null) {
      return new String[0];
    }
    String base = MatrixMessageSender.trimSlash(homeserver);
    String server = URLEncoder.encode(parsed.server, StandardCharsets.UTF_8);
    String id = URLEncoder.encode(parsed.mediaId, StandardCharsets.UTF_8);
    return new String[] {
      base + "/_matrix/client/v1/media/download/" + server + "/" + id,
      base + "/_matrix/media/v3/download/" + server + "/" + id
    };
  }

  static Mxc parseMxc(String raw) {
    if (raw == null || !raw.startsWith(MXC_PREFIX)) {
      return null;
    }
    String rest = raw.substring(MXC_PREFIX.length());
    int slash = rest.indexOf('/');
    if (slash <= 0 || slash == rest.length() - 1) {
      return null;
    }
    String server = rest.substring(0, slash);
    String mediaId = rest.substring(slash + 1);
    if (server.isBlank() || mediaId.isBlank() || mediaId.contains("/")) {
      return null;
    }
    return new Mxc(server, mediaId);
  }

  private InboundAttachment downloadOrKeep(String messageId, InboundAttachment attachment) {
    String type = attachment.type();
    String name = attachment.fileName();
    String[] urls = downloadUrls(homeserver, attachment.reference());
    if (urls.length == 0) {
      return attachment;
    }
    Exception last = null;
    for (String url : urls) {
      try {
        return fetchToDisk(messageId, attachment, type, name, url);
      } catch (Exception e) {
        last = e;
      }
    }
    LOG.warn(
        "Matrix 渠道 {} 下载媒体失败（mxc={}）：{}，保留引用",
        sanitize(channelName),
        sanitize(attachment.reference()),
        sanitize(last == null ? "" : last.getMessage()));
    return attachment;
  }

  private InboundAttachment fetchToDisk(
      String messageId, InboundAttachment attachment, String type, String name, String url)
      throws Exception {
    guard.check(url);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException("HTTP " + response.statusCode());
    }
    byte[] bytes = response.body();
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("空文件");
    }
    janitor.ensureQuotaOrThrow(mediaRoot);
    Path dir = mediaRoot.resolve(InboundMediaPaths.safeSegment(messageId));
    Files.createDirectories(dir);
    String ext = extensionOf(name);
    if (ext == null) {
      ext = DEFAULT_EXTENSION;
    }
    Path target = dir.resolve(FILE_PREFIX + ext);
    LimitedMediaWriter.writeLimited(bytes, target, InboundMediaLimits.MAX_FILE_BYTES);
    if (InboundAttachment.TYPE_IMAGE.equals(type)
        && DEFAULT_EXTENSION.equals(ext)
        && ImageMime.hasRecognizedMagic(target)) {
      String betterExt = ImageMime.extensionFor(ImageMime.probeFile(target));
      target = renameIfBetter(dir, target, ext, betterExt);
      ext = extensionOf(target.getFileName().toString());
    }
    String sniffed =
        InboundMediaExt.betterFileExtension(target, ext == null ? DEFAULT_EXTENSION : ext);
    target = renameIfBetter(dir, target, ext, sniffed);
    if (InboundAttachment.TYPE_FILE.equals(type) && ImageMime.hasRecognizedMagic(target)) {
      type = InboundAttachment.TYPE_IMAGE;
    }
    LOG.info(
        "Matrix 渠道 {} 媒体已落盘（messageId={}, type={}）",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(type));
    return new InboundAttachment(
        type, target.toAbsolutePath().toString(), attachment.reference(), name);
  }

  private Path renameIfBetter(Path dir, Path current, String currentExt, String betterExt) {
    if (betterExt == null || betterExt.isBlank() || betterExt.equals(currentExt)) {
      return current;
    }
    Path renamed = dir.resolve(FILE_PREFIX + betterExt);
    try {
      Files.move(current, renamed);
      return renamed;
    } catch (Exception ignored) {
      return current;
    }
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
    int dot = path.lastIndexOf('.');
    if (dot < 0 || dot == path.length() - 1) {
      return null;
    }
    String ext = asciiLower(path.substring(dot));
    if (!ext.matches("\\.[a-z0-9]{1,8}")) {
      return null;
    }
    return ext;
  }

  private static String asciiLower(String value) {
    if (value == null) {
      return "";
    }
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
    return InboundMediaPaths.sanitizeLog(value);
  }

  record Mxc(String server, String mediaId) {}
}
