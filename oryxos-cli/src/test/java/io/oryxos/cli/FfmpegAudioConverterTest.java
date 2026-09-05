package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegAudioConverterTest {

  @TempDir Path dir;

  @Test
  @DisplayName("Whisper 原生后缀不转码")
  void whisperNativeSkipped() throws IOException {
    Path wav = dir.resolve("a.wav");
    Files.writeString(wav, "fake");
    FfmpegAudioConverter converter =
        new FfmpegAudioConverter(
            cmd -> {
              throw new AssertionError("不应启动 ffmpeg: " + cmd);
            },
            "ffmpeg",
            Duration.ofSeconds(5));
    assertEquals(wav, converter.toWhisperWav(wav));
  }

  @Test
  @DisplayName("silk 触发 ffmpeg 并写出 wav")
  void convertsSilkViaFakeProcess() throws Exception {
    Path silk = dir.resolve("v.silk");
    Files.write(silk, "#!SILK_V3data".getBytes(StandardCharsets.US_ASCII));
    AtomicReference<List<String>> seen = new AtomicReference<>();
    FfmpegAudioConverter converter =
        new FfmpegAudioConverter(
            cmd -> {
              seen.set(List.copyOf(cmd));
              Path out = Path.of(cmd.get(cmd.size() - 1));
              try {
                Files.writeString(out, "RIFF....WAVEfmt ");
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
              return new SuccessfulProcess();
            },
            "ffmpeg-bin",
            Duration.ofSeconds(5));
    Path wav = converter.toWhisperWav(silk);
    assertTrue(Files.isRegularFile(wav));
    assertTrue(wav.getFileName().toString().endsWith(".wav"));
    assertEquals("ffmpeg-bin", seen.get().get(0));
    assertTrue(seen.get().contains("-ar"));
    Files.deleteIfExists(wav);
  }

  @Test
  @DisplayName("启动失败包装为需安装 ffmpeg")
  void missingBinaryMessage() throws IOException {
    Path silk = dir.resolve("v.bin");
    Files.write(silk, "#!SILK_V3".getBytes(StandardCharsets.US_ASCII));
    FfmpegAudioConverter converter =
        new FfmpegAudioConverter(
            cmd -> {
              throw new IllegalStateException("Cannot run program");
            },
            "ffmpeg",
            Duration.ofSeconds(5));
    IOException ex = assertThrows(IOException.class, () -> converter.toWhisperWav(silk));
    assertTrue(ex.getMessage().contains(FfmpegAudioConverter.ERR_FFMPEG_MISSING));
  }

  @Test
  @DisplayName("needsFfmpegConversion：ogg 魔数不转；silk 转")
  void needsConversionFlags() {
    byte[] ogg = "OggS".getBytes(StandardCharsets.US_ASCII);
    byte[] silk = "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);
    assertFalse(FfmpegAudioConverter.needsFfmpegConversion(Path.of("a.bin"), ogg));
    assertTrue(FfmpegAudioConverter.needsFfmpegConversion(Path.of("a.bin"), silk));
    assertFalse(FfmpegAudioConverter.needsFfmpegConversion(Path.of("a.wav"), silk));
    assertTrue(FfmpegAudioConverter.looksLikeVideo(Path.of("v.mp4")));
  }

  @Test
  @DisplayName("视频抽轨传入 -vn 与 -t")
  void videoExtractPassesVn() throws Exception {
    Path mp4 = dir.resolve("clip.mp4");
    Files.writeString(mp4, "fake-mp4");
    AtomicReference<List<String>> seen = new AtomicReference<>();
    FfmpegAudioConverter converter =
        new FfmpegAudioConverter(
            cmd -> {
              seen.set(List.copyOf(cmd));
              Path out = Path.of(cmd.get(cmd.size() - 1));
              try {
                Files.writeString(out, "RIFF....WAVEfmt ");
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
              return new SuccessfulProcess();
            },
            "ffmpeg-bin",
            Duration.ofSeconds(5));
    Path wav = converter.toWhisperWav(mp4, true);
    assertTrue(seen.get().contains("-vn"));
    assertTrue(seen.get().contains("-t"));
    Files.deleteIfExists(wav);
  }

  private static final class SuccessfulProcess extends Process {
    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public java.io.InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public java.io.InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public boolean waitFor(long timeout, java.util.concurrent.TimeUnit unit) {
      return true;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}

    @Override
    public Process destroyForcibly() {
      return this;
    }

    @Override
    public boolean isAlive() {
      return false;
    }
  }
}
