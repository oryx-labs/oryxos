package io.oryxos.core.channel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** 流式写盘并强制字节上限：超限删目标并抛错，避免「整包进内存再判」与「先写再删」不一致。 */
public final class LimitedMediaWriter {

  private LimitedMediaWriter() {}

  /**
   * 将 {@code in} 写入 {@code target}，最多 {@code maxBytes} 字节。
   *
   * @throws IOException 超限或 IO 失败（超限时尽力删除 target）
   */
  public static long copyLimited(InputStream in, Path target, long maxBytes) throws IOException {
    if (in == null) {
      throw new IOException("输入流为空");
    }
    if (maxBytes <= 0) {
      throw new IOException("入站文件上限无效");
    }
    long written = 0L;
    byte[] buf = new byte[8192];
    try (OutputStream out = Files.newOutputStream(target)) {
      int n;
      while ((n = in.read(buf)) >= 0) {
        if (n == 0) {
          continue;
        }
        written += n;
        if (written > maxBytes) {
          throw new IOException("入站文件超过上限 " + maxBytes + " 字节");
        }
        out.write(buf, 0, n);
      }
    } catch (IOException e) {
      deleteQuietly(target);
      throw e;
    }
    if (written == 0) {
      deleteQuietly(target);
      throw new IOException("下载临时文件为空");
    }
    return written;
  }

  /** 整包字节写盘（调用方已限长时用）。 */
  public static void writeLimited(byte[] bytes, Path target, long maxBytes) throws IOException {
    if (bytes == null || bytes.length == 0) {
      throw new IOException("下载临时文件为空");
    }
    if (bytes.length > maxBytes) {
      throw new IOException("入站文件超过上限 " + maxBytes + " 字节");
    }
    Files.write(target, bytes);
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
}
