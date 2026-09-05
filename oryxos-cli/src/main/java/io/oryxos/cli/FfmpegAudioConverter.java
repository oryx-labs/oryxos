package io.oryxos.cli;

import io.oryxos.core.channel.InboundMediaLimits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 调用本机 ffmpeg 将 silk/amr/视频音轨等转成 Whisper 友好的 wav（16kHz mono）。
 *
 * <p>可执行文件：环境变量 {@code ORYXOS_FFMPEG}，否则 {@code ffmpeg}（依赖 PATH）。
 */
public final class FfmpegAudioConverter {

  static final String ERR_FFMPEG_MISSING = "需安装 ffmpeg";
  static final String ERR_FFMPEG_FAILED = "ffmpeg 转码失败";

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(90);
  private static final int SAMPLE_RATE = 16_000;
  private static final int EXIT_COMMAND_NOT_FOUND = 127;
  private static final String EXT_WAV = ".wav";
  private static final int STDERR_PREVIEW_MAX = 800;

  private final Function<List<String>, Process> processStarter;
  private final String ffmpegBinary;
  private final Duration timeout;

  public FfmpegAudioConverter() {
    this(FfmpegAudioConverter::startProcess, resolveBinaryFromEnv(), DEFAULT_TIMEOUT);
  }

  FfmpegAudioConverter(
      Function<List<String>, Process> processStarter, String ffmpegBinary, Duration timeout) {
    this.processStarter = processStarter;
    this.ffmpegBinary = ffmpegBinary;
    this.timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
  }

  static String resolveBinaryFromEnv() {
    String configured = System.getenv("ORYXOS_FFMPEG");
    if (configured != null && !configured.isBlank()) {
      return configured.strip();
    }
    return "ffmpeg";
  }

  /**
   * 将 {@code input} 转为同目录临时 wav；调用方负责删除。若输入已是 Whisper 原生音频格式则原样返回（不创建临时文件）。
   *
   * @throws IOException 找不到 ffmpeg、超时或非零退出
   */
  public Path toWhisperWav(Path input) throws IOException {
    return toWhisperWav(input, false);
  }

  /**
   * @param extractAudioTrack true 时强制 {@code -vn} 抽音轨（视频）；并限制时长与输出大小
   */
  public Path toWhisperWav(Path input, boolean extractAudioTrack) throws IOException {
    if (input == null || !Files.isRegularFile(input)) {
      throw new IOException("音频文件不存在: " + input);
    }
    if (!extractAudioTrack && isWhisperNative(input)) {
      return input;
    }
    Path parent = input.getParent();
    if (parent == null) {
      parent = Path.of(".");
    }
    Path output = Files.createTempFile(parent, "oryxos-asr-", EXT_WAV);
    List<String> cmd = new ArrayList<>();
    cmd.add(ffmpegBinary);
    cmd.add("-y");
    cmd.add("-i");
    cmd.add(input.toAbsolutePath().toString());
    if (extractAudioTrack) {
      cmd.add("-vn");
      cmd.add("-t");
      cmd.add(Integer.toString(InboundMediaLimits.MAX_VIDEO_ASR_SECONDS));
    }
    cmd.add("-ar");
    cmd.add(Integer.toString(SAMPLE_RATE));
    cmd.add("-ac");
    cmd.add("1");
    cmd.add(output.toAbsolutePath().toString());
    runFfmpeg(cmd, output);
    long size = Files.size(output);
    if (size > InboundMediaLimits.MAX_ASR_UPLOAD_BYTES) {
      deleteQuietly(output);
      throw new IOException(
          ERR_FFMPEG_FAILED + ": 输出超过 " + InboundMediaLimits.MAX_ASR_UPLOAD_BYTES + " 字节");
    }
    return output;
  }

  private void runFfmpeg(List<String> cmd, Path output) throws IOException {
    Process process;
    try {
      process = processStarter.apply(cmd);
    } catch (RuntimeException e) {
      deleteQuietly(output);
      throw new IOException(
          ERR_FFMPEG_MISSING + "（ORYXOS_FFMPEG 或 PATH 中的 ffmpeg）: " + e.getMessage(), e);
    }
    if (process == null) {
      deleteQuietly(output);
      throw new IOException(ERR_FFMPEG_MISSING + "（无法启动进程）");
    }
    try {
      String stderr = readLimited(process.getErrorStream());
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        deleteQuietly(output);
        throw new IOException(ERR_FFMPEG_FAILED + ": 超时");
      }
      int code = process.exitValue();
      if (code != 0) {
        deleteQuietly(output);
        if (looksLikeMissingBinary(stderr, code)) {
          throw new IOException(ERR_FFMPEG_MISSING + "（请安装 ffmpeg 或设置 ORYXOS_FFMPEG）");
        }
        throw new IOException(ERR_FFMPEG_FAILED + " (exit=" + code + "): " + stderr);
      }
      if (!Files.isRegularFile(output) || Files.size(output) == 0) {
        deleteQuietly(output);
        throw new IOException(ERR_FFMPEG_FAILED + ": 输出为空");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      deleteQuietly(output);
      throw new IOException(ERR_FFMPEG_FAILED + ": 中断", e);
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 音频扩展名做 Locale.ROOT 小写匹配")
  static boolean isWhisperNative(Path file) {
    Path name = file.getFileName();
    if (name == null) {
      return false;
    }
    String lower = name.toString().toLowerCase(Locale.ROOT);
    return lower.endsWith(".ogg")
        || lower.endsWith(".oga")
        || lower.endsWith(".mp3")
        || lower.endsWith(".wav")
        || lower.endsWith(".m4a")
        || lower.endsWith(".webm")
        || lower.endsWith(".flac");
  }

  /** 路径像视频容器（需 -vn 抽轨，不可当原生音频直传 Whisper）。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 视频扩展名做 Locale.ROOT 小写匹配")
  static boolean looksLikeVideo(Path file) {
    Path name = file.getFileName();
    if (name == null) {
      return false;
    }
    String lower = name.toString().toLowerCase(Locale.ROOT);
    return lower.endsWith(".mp4")
        || lower.endsWith(".mov")
        || lower.endsWith(".mkv")
        || lower.endsWith(".avi");
  }

  static boolean needsFfmpegConversion(Path file, byte[] header) {
    if (isWhisperNative(file)) {
      return false;
    }
    if (looksLikeVideo(file)) {
      return true;
    }
    if (io.oryxos.core.session.InboundMediaExt.isOggMagic(header)) {
      return false;
    }
    return true;
  }

  private static boolean looksLikeMissingBinary(String stderr, int code) {
    if (code == EXIT_COMMAND_NOT_FOUND) {
      return true;
    }
    if (stderr == null) {
      return false;
    }
    String lower = stderr.toLowerCase(Locale.ROOT);
    return lower.contains("no such file")
        || lower.contains("not found")
        || lower.contains("cannot find");
  }

  private static String readLimited(InputStream in) {
    if (in == null) {
      return "";
    }
    try {
      byte[] buf = in.readNBytes(STDERR_PREVIEW_MAX);
      return new String(buf, StandardCharsets.UTF_8).replace('\r', ' ').replace('\n', ' ').strip();
    } catch (IOException e) {
      return "";
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // best-effort
    }
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "COMMAND_INJECTION",
      justification = "argv 列表传给 ProcessBuilder，不经 shell；路径来自本地落盘文件")
  private static Process startProcess(List<String> command) {
    try {
      return new ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE).start();
    } catch (IOException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }
}
