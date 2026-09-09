package io.oryxos.channel.mattermost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mattermost 入站图/文件：Outgoing Webhook 无附件字段，用 PAT 调 {@code GET /api/v4/posts/{id}} 取 {@code
 * file_ids}，再 {@code GET /api/v4/files/{id}} 落盘。失败保留 file_id 引用，不阻断文字编排。
 */
final class MattermostInboundMediaResolver {

  private static final Logger LOG = LoggerFactory.getLogger(MattermostInboundMediaResolver.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final int HTTP_OK_MIN = 200;
  private static final int HTTP_OK_MAX_EXCLUSIVE = 300;
  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String EXT_DOT = ".";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final Set<String> IMAGE_EXT =
      Set.of(".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic");
  private static final Set<String> AUDIO_EXT =
      Set.of(".mp3", ".wav", ".ogg", ".m4a", ".aac", ".amr");
  private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".mov", ".webm", ".mkv");
  private static final String MIME_IMAGE_PREFIX = "image/";
  private static final String MIME_AUDIO_PREFIX = "audio/";
  private static final String MIME_VIDEO_PREFIX = "video/";
  private static final String SAFE_FILE_ID = "[A-Za-z0-9_-]+";

  private final HttpClient http;
  private final OutboundGuard guard;
  private final String baseUrl;
  private final String token;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;

  MattermostInboundMediaResolver(
      OutboundGuard guard, String baseUrl, String token, Path mediaRoot, String channelName) {
    this(
        HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
        guard,
        baseUrl,
        token,
        mediaRoot,
        channelName,
        InboundMediaJanitor.fromEnv());
  }

  MattermostInboundMediaResolver(
      HttpClient http,
      OutboundGuard guard,
      String baseUrl,
      String token,
      Path mediaRoot,
      String channelName,
      InboundMediaJanitor janitor) {
    this.http = http;
    this.guard = guard;
    this.baseUrl = MattermostMessageSender.trimSlash(baseUrl);
    this.token = token;
    this.mediaRoot = mediaRoot;
    this.channelName = channelName;
    this.janitor = janitor == null ? InboundMediaJanitor.fromEnv() : janitor;
  }

  InboundMessage resolve(InboundMessage message) {
    return download(discover(message));
  }

  InboundMessage discover(InboundMessage message) {
    if (message == null || message.messageId() == null || message.messageId().isBlank()) {
      return message;
    }
    List<FileSpec> specs = fetchFileSpecs(message.messageId());
    if (specs.isEmpty()) {
      return message;
    }
    List<InboundAttachment> attachments = new ArrayList<>(specs.size());
    for (FileSpec spec : specs) {
      String type = classifyType(spec.mimeType(), spec.name(), spec.extension());
      attachments.add(new InboundAttachment(type, null, spec.id(), spec.name()));
    }
    boolean textual = message.textual() || !message.content().isBlank();
    return withAttachments(message, attachments, textual);
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
      FileSpec spec =
          new FileSpec(
              attachment.reference(),
              attachment.fileName() == null ? "" : attachment.fileName(),
              "",
              "");
      InboundAttachment next = downloadOrKeep(message.messageId(), spec, attachment.type());
      changed |= next != attachment;
      resolved.add(next);
    }
    if (!changed) {
      return message;
    }
    return withAttachments(message, resolved, message.textual() || !message.content().isBlank());
  }

  private static InboundMessage withAttachments(
      InboundMessage message, List<InboundAttachment> attachments, boolean textual) {
    return new InboundMessage(
        message.channelType(),
        message.channelName(),
        message.messageId(),
        message.chatKind(),
        message.userId(),
        message.chatId(),
        message.content(),
        textual,
        message.mentionedBot(),
        attachments);
  }

  private static boolean needsDownload(InboundAttachment attachment) {
    return attachment != null
        && attachment.reference() != null
        && !attachment.reference().isBlank()
        && (attachment.url() == null || attachment.url().isBlank());
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

  static List<FileSpec> filesFromPost(JsonNode post) {
    List<FileSpec> out = new ArrayList<>();
    if (post == null) {
      return out;
    }
    JsonNode files = post.path("metadata").path("files");
    if (files.isArray()) {
      for (JsonNode file : files) {
        String id = file.path("id").asText("");
        if (id.isBlank() || !id.matches(SAFE_FILE_ID)) {
          continue;
        }
        out.add(
            new FileSpec(
                id,
                file.path("name").asText(""),
                file.path("mime_type").asText(""),
                file.path("extension").asText("")));
      }
    }
    if (!out.isEmpty()) {
      return out;
    }
    JsonNode ids = post.path("file_ids");
    if (ids.isArray()) {
      for (JsonNode idNode : ids) {
        String id = idNode.asText("");
        if (!id.isBlank() && id.matches(SAFE_FILE_ID)) {
          out.add(new FileSpec(id, "", "", ""));
        }
      }
    }
    return out;
  }

  static String classifyType(String mimeType, String fileName, String extension) {
    String mime = asciiLower(mimeType);
    String ext = normalizeExt(extension);
    if (ext == null) {
      ext = extensionOf(fileName);
    }
    if (matchesMimeOrExt(mime, MIME_IMAGE_PREFIX, ext, IMAGE_EXT)) {
      return InboundAttachment.TYPE_IMAGE;
    }
    if (matchesMimeOrExt(mime, MIME_AUDIO_PREFIX, ext, AUDIO_EXT)) {
      return InboundAttachment.TYPE_AUDIO;
    }
    if (matchesMimeOrExt(mime, MIME_VIDEO_PREFIX, ext, VIDEO_EXT)) {
      return InboundAttachment.TYPE_VIDEO;
    }
    return InboundAttachment.TYPE_FILE;
  }

  private static boolean matchesMimeOrExt(
      String mime, String mimePrefix, String ext, Set<String> extensions) {
    if (mime.startsWith(mimePrefix)) {
      return true;
    }
    return ext != null && extensions.contains(ext);
  }

  /** Webhook 无 mime 时，用事件侧已推断的 type 补齐。 */
  private static String applyFallbackType(String type, String mimeType, String fallbackType) {
    if (mimeType != null && !mimeType.isBlank()) {
      return type;
    }
    if (fallbackType == null || fallbackType.isBlank()) {
      return type;
    }
    if (!InboundAttachment.TYPE_FILE.equals(type)) {
      return type;
    }
    return fallbackType;
  }

  private List<FileSpec> fetchFileSpecs(String postId) {
    if (!postId.matches(SAFE_FILE_ID)) {
      return List.of();
    }
    String url = baseUrl + "/api/v4/posts/" + postId;
    guard.check(url);
    try {
      HttpResponse<String> response = sendGet(url);
      if (response.statusCode() < HTTP_OK_MIN || response.statusCode() >= HTTP_OK_MAX_EXCLUSIVE) {
        LOG.warn(
            "Mattermost 渠道 {} 读帖失败 HTTP {}（postId={}）",
            sanitize(channelName),
            response.statusCode(),
            sanitize(postId));
        return List.of();
      }
      return filesFromPost(MAPPER.readTree(response.body()));
    } catch (Exception e) {
      LOG.warn(
          "Mattermost 渠道 {} 读帖异常（postId={}）：{}",
          sanitize(channelName),
          sanitize(postId),
          sanitize(e.getMessage()));
      return List.of();
    }
  }

  private InboundAttachment downloadOrKeep(String messageId, FileSpec spec, String fallbackType) {
    String type = classifyType(spec.mimeType(), spec.name(), spec.extension());
    type = applyFallbackType(type, spec.mimeType(), fallbackType);
    String url = baseUrl + "/api/v4/files/" + spec.id();
    guard.check(url);
    try {
      HttpResponse<byte[]> response = sendGetBytes(url);
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
      String ext = extensionFor(spec, type);
      Path target = dir.resolve("mm-media" + ext);
      LimitedMediaWriter.writeLimited(bytes, target, InboundMediaLimits.MAX_FILE_BYTES);
      if (shouldProbeImageExtension(type, ext, target)) {
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
          "Mattermost 渠道 {} 媒体已落盘（messageId={}, type={}, fileId={}）",
          sanitize(channelName),
          sanitize(messageId),
          sanitize(type),
          sanitize(spec.id()));
      return new InboundAttachment(
          type, target.toAbsolutePath().toString(), spec.id(), spec.name());
    } catch (Exception e) {
      LOG.warn(
          "Mattermost 渠道 {} 下载媒体失败（fileId={}）：{}，保留 file_id",
          sanitize(channelName),
          sanitize(spec.id()),
          sanitize(e.getMessage()));
      if (InboundAttachment.TYPE_IMAGE.equals(type)) {
        return new InboundAttachment(type, null, spec.id(), spec.name());
      }
      return InboundAttachment.fileReference(spec.id(), spec.name());
    }
  }

  private static boolean shouldProbeImageExtension(String type, String ext, Path target) {
    if (!InboundAttachment.TYPE_IMAGE.equals(type)) {
      return false;
    }
    if (!DEFAULT_EXTENSION.equals(ext)) {
      return false;
    }
    return ImageMime.hasRecognizedMagic(target);
  }

  private Path renameIfBetter(Path dir, Path current, String currentExt, String betterExt) {
    if (betterExt == null || betterExt.isBlank() || betterExt.equals(currentExt)) {
      return current;
    }
    Path renamed = dir.resolve("mm-media" + betterExt);
    try {
      Files.move(current, renamed);
      return renamed;
    } catch (Exception ignored) {
      return current;
    }
  }

  private HttpResponse<String> sendGet(String url) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<byte[]> sendGetBytes(String url) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(TIMEOUT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }

  private static String extensionFor(FileSpec spec, String type) {
    String fromName = extensionOf(spec.name());
    if (fromName != null) {
      return fromName;
    }
    String fromExt = normalizeExt(spec.extension());
    if (fromExt != null) {
      return fromExt;
    }
    if (InboundAttachment.TYPE_IMAGE.equals(type)) {
      return DEFAULT_EXTENSION;
    }
    return DEFAULT_EXTENSION;
  }

  private static String normalizeExt(String extension) {
    if (extension == null || extension.isBlank()) {
      return null;
    }
    String ext = extension.strip();
    if (!ext.startsWith(EXT_DOT)) {
      ext = EXT_DOT + ext;
    }
    ext = asciiLower(ext);
    if (!ext.matches(SAFE_EXTENSION_PATTERN)) {
      return null;
    }
    return ext;
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

  record FileSpec(String id, String name, String mimeType, String extension) {}
}
