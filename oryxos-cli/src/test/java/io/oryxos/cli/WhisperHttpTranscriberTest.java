package io.oryxos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WhisperHttpTranscriberTest {

  @TempDir Path dir;
  private HttpServer server;
  private String base;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/audio/transcriptions",
        ex -> {
          byte[] body = "{\"text\":\"你好\"}".getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("mp4 走 -vn 抽轨后再上传，不整文件当音频")
  void videoUsesExtractTrack() throws Exception {
    Path mp4 = dir.resolve("clip.mp4");
    // 故意写大一点的假内容：若整文件进 ffmpeg 输入路径仍 OK；关键是 cmd 含 -vn
    Files.write(mp4, ("fake-mp4-" + "x".repeat(2000)).getBytes(StandardCharsets.US_ASCII));
    AtomicReference<List<String>> ffmpegCmd = new AtomicReference<>();
    FfmpegAudioConverter converter =
        new FfmpegAudioConverter(
            cmd -> {
              ffmpegCmd.set(List.copyOf(cmd));
              Path out = Path.of(cmd.get(cmd.size() - 1));
              try {
                Files.writeString(out, "RIFFWAVEfmt ");
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
              return new Process() {
                @Override
                public OutputStream getOutputStream() {
                  return OutputStream.nullOutputStream();
                }

                @Override
                public java.io.InputStream getInputStream() {
                  return java.io.InputStream.nullInputStream();
                }

                @Override
                public java.io.InputStream getErrorStream() {
                  return java.io.InputStream.nullInputStream();
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
              };
            },
            "ffmpeg",
            Duration.ofSeconds(5));
    WhisperHttpTranscriber t =
        new WhisperHttpTranscriber(
            "sk-test",
            base,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
            converter);
    String text = t.transcribe(mp4);
    assertEquals("你好", text);
    assertTrue(ffmpegCmd.get().contains("-vn"));
  }
}
