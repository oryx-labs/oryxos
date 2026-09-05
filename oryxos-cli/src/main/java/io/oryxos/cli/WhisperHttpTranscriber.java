package io.oryxos.cli;

import io.oryxos.core.channel.InboundMediaLimits;
import io.oryxos.core.channel.InboundSpeechTranscriber;
import io.oryxos.core.session.InboundMediaExt;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * OpenAI 兼容 Whisper 转写（{@code POST /v1/audio/transcriptions}）。 环境变量：{@code ORYXOS_ASR_API_KEY} 或
 * {@code OPENAI_API_KEY}；可选 {@code ORYXOS_ASR_BASE_URL} / {@code OPENAI_BASE_URL}（默认 {@code
 * https://api.openai.com}）。silk/amr/视频音轨等经 {@link FfmpegAudioConverter} 转 wav 后再上传。
 *
 * <p>只读文件头做魔数判断，避免视频整文件 {@code readAllBytes}；上传前仍读取最终音频字节（通常已是抽轨 wav）。
 */
public final class WhisperHttpTranscriber implements InboundSpeechTranscriber {

  private static final Duration TIMEOUT = Duration.ofSeconds(120);
  private static final String DEFAULT_BASE = "https://api.openai.com";
  private static final String PATH = "/v1/audio/transcriptions";
  private static final int HTTP_STATUS_OK_MIN = 200;
  private static final int HTTP_STATUS_OK_MAX_EXCLUSIVE = 300;
  private static final int ERROR_BODY_PREVIEW_MAX = 200;
  private static final String TRAILING_SLASH = "/";
  private static final String FALLBACK_AUDIO_NAME = "audio.bin";
  private static final String VOICE_OGG_NAME = "voice.ogg";
  private static final String VOICE_WAV_NAME = "voice.wav";
  private static final String EXT_OGG = ".ogg";
  private static final String EXT_OGA = ".oga";
  private static final String EXT_MP3 = ".mp3";
  private static final String EXT_WAV = ".wav";
  private static final String EXT_M4A = ".m4a";
  private static final String EXT_WEBM = ".webm";
  private static final String EXT_FLAC = ".flac";
  private static final String EXT_SILK = ".silk";
  private static final String EXT_AMR = ".amr";

  private final String apiKey;
  private final String baseUrl;
  private final HttpClient httpClient;
  private final FfmpegAudioConverter audioConverter;

  WhisperHttpTranscriber(String apiKey, String baseUrl, HttpClient httpClient) {
    this(apiKey, baseUrl, httpClient, new FfmpegAudioConverter());
  }

  WhisperHttpTranscriber(
      String apiKey, String baseUrl, HttpClient httpClient, FfmpegAudioConverter audioConverter) {
    this.apiKey = apiKey;
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.httpClient = httpClient;
    this.audioConverter = audioConverter == null ? new FfmpegAudioConverter() : audioConverter;
  }

  /** 未配置密钥时返回 null（enricher 降级为「未配置 ASR」提示）。 */
  public static InboundSpeechTranscriber fromEnv() {
    String key =
        firstNonBlank(System.getenv("ORYXOS_ASR_API_KEY"), System.getenv("OPENAI_API_KEY"));
    if (key == null || key.isBlank()) {
      return null;
    }
    String base =
        firstNonBlank(
            System.getenv("ORYXOS_ASR_BASE_URL"), System.getenv("OPENAI_BASE_URL"), DEFAULT_BASE);
    return new WhisperHttpTranscriber(
        key.strip(), base, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
  }

  @Override
  public String transcribe(Path audioFile) throws Exception {
    if (audioFile == null || !Files.isRegularFile(audioFile)) {
      throw new IOException("音频文件不存在: " + audioFile);
    }
    long fileSize = Files.size(audioFile);
    if (fileSize == 0) {
      throw new IOException("音频文件为空");
    }
    byte[] header = readHeader(audioFile);
    Path uploadPath = audioFile;
    Path tempWav = null;
    try {
      boolean video = FfmpegAudioConverter.looksLikeVideo(audioFile);
      if (video || FfmpegAudioConverter.needsFfmpegConversion(audioFile, header)) {
        tempWav = audioConverter.toWhisperWav(audioFile, video);
        uploadPath = tempWav;
      } else if (!FfmpegAudioConverter.isWhisperNative(audioFile)
          && InboundMediaExt.isOggMagic(header)) {
        uploadPath = audioFile;
      }
      long uploadSize = Files.size(uploadPath);
      if (uploadSize > InboundMediaLimits.MAX_ASR_UPLOAD_BYTES) {
        throw new IOException("ASR 上传超过上限 " + InboundMediaLimits.MAX_ASR_UPLOAD_BYTES + " 字节");
      }
      byte[] bodyBytes = Files.readAllBytes(uploadPath);
      String boundary = "----oryxos" + UUID.randomUUID().toString().replace("-", "");
      String fileName = whisperFileName(uploadPath, bodyBytes);
      byte[] multipart = buildMultipart(boundary, fileName, bodyBytes);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + PATH))
              .timeout(TIMEOUT)
              .header("Authorization", "Bearer " + apiKey)
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(HttpRequest.BodyPublishers.ofByteArray(multipart))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < HTTP_STATUS_OK_MIN
          || response.statusCode() >= HTTP_STATUS_OK_MAX_EXCLUSIVE) {
        String body = response.body();
        String preview =
            body == null
                ? ""
                : body.substring(0, Math.min(ERROR_BODY_PREVIEW_MAX, body.length()))
                    .replace('\n', ' ')
                    .replace('\r', ' ');
        throw new IOException("Whisper HTTP " + response.statusCode() + ": " + preview);
      }
      return extractText(response.body());
    } finally {
      if (tempWav != null && !tempWav.equals(audioFile)) {
        try {
          Files.deleteIfExists(tempWav);
        } catch (IOException ignored) {
          // best-effort
        }
      }
    }
  }

  private static byte[] readHeader(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      return in.readNBytes(InboundMediaLimits.HEADER_SNIFF_BYTES);
    }
  }

  /** Whisper 按上传文件名判格式：占位 .bin 但内容是 Ogg 时改成 .ogg；转码后的 wav 用固定名。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 音频扩展名做 Locale.ROOT 小写匹配，不参与安全/身份比较")
  static String whisperFileName(Path audioFile, byte[] bodyBytes) {
    Path fileName = audioFile.getFileName();
    String name = fileName == null ? FALLBACK_AUDIO_NAME : fileName.toString();
    String lower = name.toLowerCase(java.util.Locale.ROOT);
    if (lower.endsWith(EXT_OGG)
        || lower.endsWith(EXT_OGA)
        || lower.endsWith(EXT_MP3)
        || lower.endsWith(EXT_WAV)
        || lower.endsWith(EXT_M4A)
        || lower.endsWith(EXT_WEBM)
        || lower.endsWith(EXT_FLAC)) {
      return name;
    }
    if (InboundMediaExt.isOggMagic(bodyBytes)) {
      return VOICE_OGG_NAME;
    }
    if (lower.endsWith(EXT_SILK)
        || lower.endsWith(EXT_AMR)
        || InboundMediaExt.isSilkMagic(bodyBytes)
        || InboundMediaExt.isAmrMagic(bodyBytes)) {
      return VOICE_WAV_NAME;
    }
    return name;
  }

  private static byte[] buildMultipart(String boundary, String fileName, byte[] fileBytes) {
    String preamble =
        "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"model\"\r\n\r\n"
            + "whisper-1\r\n"
            + "--"
            + boundary
            + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\""
            + fileName.replace("\"", "")
            + "\"\r\n"
            + "Content-Type: application/octet-stream\r\n\r\n";
    String epilogue = "\r\n--" + boundary + "--\r\n";
    byte[] pre = preamble.getBytes(StandardCharsets.UTF_8);
    byte[] epi = epilogue.getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[pre.length + fileBytes.length + epi.length];
    System.arraycopy(pre, 0, all, 0, pre.length);
    System.arraycopy(fileBytes, 0, all, pre.length, fileBytes.length);
    System.arraycopy(epi, 0, all, pre.length + fileBytes.length, epi.length);
    return all;
  }

  private static String extractText(String json) throws IOException {
    if (json == null || json.isBlank()) {
      throw new IOException("Whisper 响应为空");
    }
    String marker = "\"text\"";
    int i = json.indexOf(marker);
    if (i < 0) {
      throw new IOException("Whisper 响应无 text 字段");
    }
    int colon = json.indexOf(':', i + marker.length());
    int quote = json.indexOf('"', colon + 1);
    if (quote < 0) {
      throw new IOException("Whisper 响应 text 格式异常");
    }
    StringBuilder out = new StringBuilder();
    for (int p = quote + 1; p < json.length(); p++) {
      char c = json.charAt(p);
      if (c == '\\' && p + 1 < json.length()) {
        char n = json.charAt(++p);
        out.append(
            switch (n) {
              case 'n' -> '\n';
              case 'r' -> '\r';
              case 't' -> '\t';
              case '"' -> '"';
              case '\\' -> '\\';
              default -> n;
            });
        continue;
      }
      if (c == '"') {
        break;
      }
      out.append(c);
    }
    return out.toString().strip();
  }

  private static String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isBlank()) {
      return DEFAULT_BASE;
    }
    String s = url.strip();
    while (s.endsWith(TRAILING_SLASH)) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }
}
