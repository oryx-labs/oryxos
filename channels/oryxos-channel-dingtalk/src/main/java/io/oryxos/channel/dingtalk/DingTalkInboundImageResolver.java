package io.oryxos.channel.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.oryxos.core.channel.InboundAttachment;
import io.oryxos.core.channel.InboundMediaHttp;
import io.oryxos.core.channel.InboundMediaJanitor;
import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundMediaPaths;
import io.oryxos.core.channel.InboundMessage;
import io.oryxos.core.channel.LimitedMediaWriter;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.session.ImageMime;
import io.oryxos.core.session.InboundMediaExt;
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
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 钉钉 Stream 入站图片：官方常只给 {@code downloadCode}（无直链），须换临时 URL 再落盘。成功后写入 {@link
 * InboundAttachment#url()}，Vision 才能吃到二进制。
 *
 * <p>失败保留原 reference（降级，不阻断编排）。{@code robotCode} 默认取 ClientId（{@code app_id}）。
 */
final class DingTalkInboundImageResolver {

  private static final Logger LOG = LoggerFactory.getLogger(DingTalkInboundImageResolver.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String OAUTH_PATH = "/v1.0/oauth2/accessToken";
  private static final String DOWNLOAD_META_PATH = "/v1.0/robot/messageFiles/download";
  private static final String HEADER_ACCESS_TOKEN = "x-acs-dingtalk-access-token";
  private static final String DEFAULT_EXTENSION = ".bin";
  private static final String SAFE_EXTENSION_PATTERN = "\\.[a-z0-9]{1,8}";
  private static final int DOWNLOAD_ATTEMPTS = 2;
  private static final long TOKEN_SKEW_MS = 60_000L;
  private static final long DEFAULT_TOKEN_EXPIRE_SEC = 7200L;
  private static final long MIN_TOKEN_TTL_SEC = 60L;
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final String SCHEME_HTTPS = "https";
  private static final String SCHEME_HTTP = "http";
  private static final String HOST_DINGTALK = "dingtalk.com";
  private static final String HOST_SUFFIX_DINGTALK = ".dingtalk.com";
  private static final String HOST_SUFFIX_ALICDN = ".alicdn.com";
  private static final String HOST_SUFFIX_ALIYUNCS = ".aliyuncs.com";

  private final HttpClient httpClient;
  private final OutboundGuard guard;
  private final String apiBaseUrl;
  private final String appKey;
  private final String appSecret;
  private final String robotCode;
  private final Path mediaRoot;
  private final String channelName;
  private final InboundMediaJanitor janitor;
  private final AtomicReference<CachedToken> tokenRef = new AtomicReference<>();

  DingTalkInboundImageResolver(
      OutboundGuard guard,
      String appKey,
      String appSecret,
      String robotCode,
      Path mediaRoot,
      String channelName) {
    this(
        InboundMediaHttp.newNoRedirectClient(HTTP_TIMEOUT),
        guard,
        DingTalkStreamClient.API_BASE_URL,
        appKey,
        appSecret,
        robotCode,
        mediaRoot,
        channelName,
        InboundMediaJanitor.fromEnv());
  }

  /** 单测注入：自定义 {@code apiBaseUrl}（本地 HttpServer）与 {@link HttpClient}。 */
  DingTalkInboundImageResolver(
      HttpClient httpClient,
      OutboundGuard guard,
      String apiBaseUrl,
      String appKey,
      String appSecret,
      String robotCode,
      Path mediaRoot,
      String channelName) {
    this(
        httpClient,
        guard,
        apiBaseUrl,
        appKey,
        appSecret,
        robotCode,
        mediaRoot,
        channelName,
        InboundMediaJanitor.fromEnv());
  }

  /** 单测可注入 janitor。 */
  DingTalkInboundImageResolver(
      HttpClient httpClient,
      OutboundGuard guard,
      String apiBaseUrl,
      String appKey,
      String appSecret,
      String robotCode,
      Path mediaRoot,
      String channelName,
      InboundMediaJanitor janitor) {
    this.httpClient = httpClient;
    this.guard = guard;
    this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
    this.appKey = appKey;
    this.appSecret = appSecret;
    this.robotCode = robotCode;
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

  static boolean needsDownload(InboundAttachment attachment) {
    String type = attachment.type();
    if (!InboundAttachment.TYPE_IMAGE.equals(type)
        && !InboundAttachment.TYPE_FILE.equals(type)
        && !InboundAttachment.TYPE_AUDIO.equals(type)
        && !InboundAttachment.TYPE_VIDEO.equals(type)) {
      return false;
    }
    boolean hasUrl = attachment.url() != null && !attachment.url().isBlank();
    boolean hasRef = attachment.reference() != null && !attachment.reference().isBlank();
    if (hasRef && !hasUrl) {
      return true;
    }
    // 文件/语音/视频远程 URL 必须落盘，供 enricher 给出本地路径（语音/音轨再转写）
    return (InboundAttachment.TYPE_FILE.equals(type)
            || InboundAttachment.TYPE_AUDIO.equals(type)
            || InboundAttachment.TYPE_VIDEO.equals(type))
        && hasUrl
        && ImageMime.isHttpUrl(attachment.url().strip());
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
    if (attachment.url() != null
        && !attachment.url().isBlank()
        && ImageMime.isHttpUrl(attachment.url().strip())) {
      return downloadRemoteUrlOrKeep(messageId, attachment);
    }
    String downloadCode = attachment.reference();
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        String token = accessToken();
        String downloadUrl = resolveDownloadUrl(token, downloadCode);
        Path file = writeToMediaRoot(messageId, downloadCode, downloadUrl);
        return new InboundAttachment(
            attachment.type(),
            file.toAbsolutePath().toString(),
            downloadCode,
            attachment.fileName());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        last = e;
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          LOG.warn(
              "钉钉渠道 {} 下载媒体超时重试 {}/{}（messageId={}）：{}",
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
        "钉钉渠道 {} 下载媒体失败（messageId={}, downloadCode={}）：{}，保留 downloadCode",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(downloadCode),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private InboundAttachment downloadRemoteUrlOrKeep(
      String messageId, InboundAttachment attachment) {
    String remoteUrl = attachment.url().strip();
    Exception last = null;
    for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
      try {
        Path file = writeToMediaRoot(messageId, remoteUrl, remoteUrl);
        String ref =
            attachment.reference() == null || attachment.reference().isBlank()
                ? remoteUrl
                : attachment.reference();
        return new InboundAttachment(
            attachment.type(), file.toAbsolutePath().toString(), ref, attachment.fileName());
      } catch (Exception e) {
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        last = e;
        if (attempt < DOWNLOAD_ATTEMPTS && isTransientTimeout(e)) {
          LOG.warn(
              "钉钉渠道 {} 下载远程文件超时重试 {}/{}（messageId={}）：{}",
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
        "钉钉渠道 {} 下载远程文件失败（messageId={}）：{}，保留远程 URL",
        sanitize(channelName),
        sanitize(messageId),
        sanitize(last == null ? null : last.getMessage()));
    return attachment;
  }

  private String accessToken() throws IOException, InterruptedException {
    CachedToken cached = tokenRef.get();
    long now = System.currentTimeMillis();
    if (cached != null && now < cached.expiresAtMillis()) {
      return cached.token();
    }
    guard.check(apiBaseUrl);
    ObjectNode body = MAPPER.createObjectNode();
    body.put("appKey", appKey);
    body.put("appSecret", appSecret);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + OAUTH_PATH))
            .timeout(HTTP_TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < HTTP_STATUS_OK_MIN
        || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException("oauth2 accessToken HTTP " + response.statusCode());
    }
    JsonNode root = MAPPER.readTree(response.body());
    String token = root.path("accessToken").asText(null);
    if (token == null || token.isBlank()) {
      throw new IllegalStateException("oauth2 accessToken 响应缺 accessToken");
    }
    long expireInSec = root.path("expireIn").asLong(DEFAULT_TOKEN_EXPIRE_SEC);
    long expiresAt = now + Math.max(MIN_TOKEN_TTL_SEC, expireInSec) * 1000L - TOKEN_SKEW_MS;
    tokenRef.set(new CachedToken(token, expiresAt));
    return token;
  }

  private String resolveDownloadUrl(String accessToken, String downloadCode)
      throws IOException, InterruptedException {
    guard.check(apiBaseUrl);
    ObjectNode body = MAPPER.createObjectNode();
    body.put("downloadCode", downloadCode);
    body.put("robotCode", robotCode);
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(apiBaseUrl + DOWNLOAD_META_PATH))
            .timeout(HTTP_TIMEOUT)
            .header("Content-Type", "application/json")
            .header(HEADER_ACCESS_TOKEN, accessToken)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < HTTP_STATUS_OK_MIN
        || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
      throw new IllegalStateException("messageFiles/download HTTP " + response.statusCode());
    }
    String downloadUrl = MAPPER.readTree(response.body()).path("downloadUrl").asText(null);
    if (downloadUrl == null || downloadUrl.isBlank()) {
      throw new IllegalStateException("messageFiles/download 响应缺 downloadUrl");
    }
    return downloadUrl;
  }

  private Path writeToMediaRoot(String messageId, String downloadCode, String downloadUrl)
      throws Exception {
    URI uri = URI.create(downloadUrl);
    if (!isAllowedMediaUri(uri)) {
      throw new IllegalStateException("拒绝非钉钉域临时下载地址: " + sanitize(uri.getHost()));
    }
    HttpResponse<byte[]> response =
        InboundMediaHttp.getFollowingAllowlist(
            httpClient, uri, HTTP_TIMEOUT, this::isAllowedMediaUri);
    byte[] bytes = response.body();
    if (bytes == null || bytes.length == 0) {
      throw new IllegalStateException("下载临时文件为空");
    }
    if (bytes.length > InboundMediaLimits.MAX_FILE_BYTES) {
      throw new IllegalStateException("入站文件超过上限 " + InboundMediaLimits.MAX_FILE_BYTES + " 字节");
    }
    String ext = extensionOf(uri.getPath());
    Path dir = mediaRoot.resolve(safeSegment(messageId));
    Files.createDirectories(dir);
    Path target = dir.resolve(safeSegment(downloadCode) + ext);
    janitor.ensureQuotaOrThrow(mediaRoot);
    LimitedMediaWriter.writeLimited(bytes, target, InboundMediaLimits.MAX_FILE_BYTES);
    // ext 经 asciiLower；用 equals 避免 equalsIgnoreCase 触发 SpotBugs IMPROPER_UNICODE
    if (DEFAULT_EXTENSION.equals(ext) || InboundMediaExt.EXT_FILE.equals(ext)) {
      String sniffed = ImageMime.probeFile(target);
      String betterExt = ImageMime.extensionFor(sniffed);
      if (!DEFAULT_EXTENSION.equals(betterExt)
          && !betterExt.equals(ext)
          && ImageMime.hasRecognizedMagic(target)) {
        Path renamed = dir.resolve(safeSegment(downloadCode) + betterExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("钉钉图片重命名扩展名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
      String pdfExt = InboundMediaExt.betterFileExtension(target, ext);
      if (pdfExt != null) {
        Path renamed = dir.resolve(safeSegment(downloadCode) + pdfExt);
        try {
          Files.move(target, renamed);
          return renamed;
        } catch (IOException moveFailed) {
          LOG.debug("钉钉文件 PDF 扩展名重命名失败，保留原文件: {}", sanitize(moveFailed.getMessage()));
        }
      }
    }
    return target;
  }

  private boolean isAllowedMediaUri(URI uri) {
    if (uri == null || uri.getHost() == null || uri.getScheme() == null) {
      return false;
    }
    String scheme = asciiLower(uri.getScheme());
    if (!SCHEME_HTTPS.equals(scheme) && !SCHEME_HTTP.equals(scheme)) {
      return false;
    }
    // 单测：apiBase 指向本地 HttpServer 时允许同主机
    URI api = URI.create(apiBaseUrl);
    String apiHost = api.getHost();
    String mediaHost = asciiLower(uri.getHost());
    if (apiHost != null && asciiLower(apiHost).equals(mediaHost)) {
      return true;
    }
    // 钉钉 messageFiles 临时链常落在 OSS（*.aliyuncs.com），可能是 http 或 https
    return isAllowedMediaHost(mediaHost);
  }

  static boolean isAllowedMediaHost(String mediaHost) {
    if (mediaHost == null || mediaHost.isBlank()) {
      return false;
    }
    return HOST_DINGTALK.equals(mediaHost)
        || mediaHost.endsWith(HOST_SUFFIX_DINGTALK)
        || mediaHost.endsWith(HOST_SUFFIX_ALICDN)
        || mediaHost.endsWith(HOST_SUFFIX_ALIYUNCS);
  }

  /** ASCII-only 小写，避免 SpotBugs IMPROPER_UNICODE（scheme/host 均为 ASCII）。 */
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
    return InboundMediaPaths.safeSegment(raw);
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isBlank()) {
      return DingTalkStreamClient.API_BASE_URL;
    }
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private static String sanitize(String value) {
    // 内联替换：SpotBugs CRLF_INJECTION_LOGS 需在本类内可见的 \r/\n 清洗
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  private record CachedToken(String token, long expiresAtMillis) {}
}
