package io.oryxos.core.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** 图片 MIME / 扩展名推断：优先文件魔数，其次路径后缀；均未知时默认 {@code image/jpeg}（IM 入站图常见）。 */
public final class ImageMime {

  public static final String IMAGE_JPEG = "image/jpeg";
  public static final String IMAGE_PNG = "image/png";
  public static final String IMAGE_GIF = "image/gif";
  public static final String IMAGE_WEBP = "image/webp";

  public static final String HTTP_PREFIX = "http://";
  public static final String HTTPS_PREFIX = "https://";

  private static final String EXT_JPG = ".jpg";
  private static final String EXT_JPEG = ".jpeg";
  private static final String EXT_PNG = ".png";
  private static final String EXT_GIF = ".gif";
  private static final String EXT_WEBP = ".webp";

  private static final int MAGIC_HEADER_BYTES = 12;
  private static final int JPEG_MAGIC_MIN = 3;
  private static final int PNG_MAGIC_MIN = 8;
  private static final int GIF_MAGIC_MIN = 6;
  private static final int WEBP_MAGIC_MIN = 12;

  private ImageMime() {}

  /** 是否为远程 http(s) URL（用于区分本地绝对路径）。 */
  public static boolean isHttpUrl(String uri) {
    return uri != null && (uri.startsWith(HTTP_PREFIX) || uri.startsWith(HTTPS_PREFIX));
  }

  /** 由本地文件推断 MIME；读魔数失败则回落到路径后缀，再默认 JPEG。 */
  public static String probeFile(Path file) {
    if (file == null) {
      return IMAGE_JPEG;
    }
    String fromMagic = sniffMagic(file);
    if (fromMagic != null) {
      return fromMagic;
    }
    return fromPath(file.toString());
  }

  /** 本地文件是否具备可识别的图片魔数（jpeg/png/gif/webp）；密文或损坏文件为 false。 */
  public static boolean hasRecognizedMagic(Path file) {
    return sniffMagic(file) != null;
  }

  /** 字节头是否为可识别图片魔数。 */
  public static boolean hasRecognizedMagic(byte[] header) {
    return sniffMagicBytes(header) != null;
  }

  /** 由路径或 URL 字符串的后缀推断；无后缀则默认 JPEG。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 扩展名做 Locale.ROOT 小写匹配以判 MIME，不参与安全/身份比较")
  public static String fromPath(String pathOrUrl) {
    if (pathOrUrl == null || pathOrUrl.isBlank()) {
      return IMAGE_JPEG;
    }
    String lower = pathOrUrl.toLowerCase(Locale.ROOT);
    int q = lower.indexOf('?');
    if (q >= 0) {
      lower = lower.substring(0, q);
    }
    if (lower.endsWith(EXT_PNG)) {
      return IMAGE_PNG;
    }
    if (lower.endsWith(EXT_GIF)) {
      return IMAGE_GIF;
    }
    if (lower.endsWith(EXT_WEBP)) {
      return IMAGE_WEBP;
    }
    if (lower.endsWith(EXT_JPG) || lower.endsWith(EXT_JPEG)) {
      return IMAGE_JPEG;
    }
    return IMAGE_JPEG;
  }

  /** MIME → 安全扩展名（含点）；未知回落 {@code .jpg}。 */
  public static String extensionFor(String mimeType) {
    if (IMAGE_PNG.equals(mimeType)) {
      return EXT_PNG;
    }
    if (IMAGE_GIF.equals(mimeType)) {
      return EXT_GIF;
    }
    if (IMAGE_WEBP.equals(mimeType)) {
      return EXT_WEBP;
    }
    return EXT_JPG;
  }

  private static String sniffMagic(Path file) {
    if (!Files.isRegularFile(file)) {
      return null;
    }
    try (InputStream in = Files.newInputStream(file)) {
      return sniffMagicBytes(in.readNBytes(MAGIC_HEADER_BYTES));
    } catch (IOException e) {
      return null;
    }
  }

  private static String sniffMagicBytes(byte[] header) {
    if (header == null) {
      return null;
    }
    if (header.length >= JPEG_MAGIC_MIN
        && (header[0] & 0xFF) == 0xFF
        && (header[1] & 0xFF) == 0xD8
        && (header[2] & 0xFF) == 0xFF) {
      return IMAGE_JPEG;
    }
    if (header.length >= PNG_MAGIC_MIN
        && header[0] == (byte) 0x89
        && header[1] == 0x50
        && header[2] == 0x4E
        && header[3] == 0x47) {
      return IMAGE_PNG;
    }
    if (header.length >= GIF_MAGIC_MIN
        && header[0] == 'G'
        && header[1] == 'I'
        && header[2] == 'F'
        && header[3] == '8') {
      return IMAGE_GIF;
    }
    if (header.length >= WEBP_MAGIC_MIN
        && header[0] == 'R'
        && header[1] == 'I'
        && header[2] == 'F'
        && header[3] == 'F'
        && header[8] == 'W'
        && header[9] == 'E'
        && header[10] == 'B'
        && header[11] == 'P') {
      return IMAGE_WEBP;
    }
    return null;
  }
}
