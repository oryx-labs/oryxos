package io.oryxos.core.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Predicate;

/** 入站非图片文件的扩展名嗅探（PDF、Ogg/Opus、Silk、AMR）。 与 {@link ImageMime} 分离。 */
public final class InboundMediaExt {

  public static final String EXT_PDF = ".pdf";
  public static final String EXT_OGG = ".ogg";
  public static final String EXT_SILK = ".silk";
  public static final String EXT_AMR = ".amr";
  public static final String EXT_BIN = ".bin";
  public static final String EXT_FILE = ".file";

  private static final int PDF_MAGIC_LEN = 4;
  private static final int OGG_MAGIC_LEN = 4;
  private static final byte PDF_B0 = '%';
  private static final byte PDF_B1 = 'P';
  private static final byte PDF_B2 = 'D';
  private static final byte PDF_B3 = 'F';
  private static final byte OGG_B0 = 'O';
  private static final byte OGG_B1 = 'g';
  private static final byte OGG_B2 = 'g';
  private static final byte OGG_B3 = 'S';

  /** 腾讯/飞书常见 Silk：{@code #!SILK_V3}。 */
  private static final byte[] SILK_MAGIC = "#!SILK_V3".getBytes(StandardCharsets.US_ASCII);

  /** AMR-NB：{@code #!AMR\n}；AMR-WB：{@code #!AMR-WB\n}。 */
  private static final byte[] AMR_MAGIC = "#!AMR".getBytes(StandardCharsets.US_ASCII);

  private InboundMediaExt() {}

  /** 本地文件头是否为 PDF（{@code %PDF}）。 */
  public static boolean isPdfMagic(Path file) {
    return magicMatches(file, PDF_MAGIC_LEN, InboundMediaExt::isPdfMagic);
  }

  /** 字节头是否为 PDF。 */
  public static boolean isPdfMagic(byte[] header) {
    return header != null
        && header.length >= PDF_MAGIC_LEN
        && header[0] == PDF_B0
        && header[1] == PDF_B1
        && header[2] == PDF_B2
        && header[3] == PDF_B3;
  }

  /** 本地文件头是否为 Ogg（{@code OggS}，飞书语音常见 Opus/Ogg）。 */
  public static boolean isOggMagic(Path file) {
    return magicMatches(file, OGG_MAGIC_LEN, InboundMediaExt::isOggMagic);
  }

  /** 字节头是否为 Ogg。 */
  public static boolean isOggMagic(byte[] header) {
    return header != null
        && header.length >= OGG_MAGIC_LEN
        && header[0] == OGG_B0
        && header[1] == OGG_B1
        && header[2] == OGG_B2
        && header[3] == OGG_B3;
  }

  /** 本地文件头是否为 Silk。 */
  public static boolean isSilkMagic(Path file) {
    return magicMatches(file, SILK_MAGIC.length, InboundMediaExt::isSilkMagic);
  }

  /** 字节头是否为 Silk（{@code #!SILK_V3}）。 */
  public static boolean isSilkMagic(byte[] header) {
    return startsWith(header, SILK_MAGIC);
  }

  /** 本地文件头是否为 AMR。 */
  public static boolean isAmrMagic(Path file) {
    return magicMatches(file, AMR_MAGIC.length, InboundMediaExt::isAmrMagic);
  }

  /** 字节头是否为 AMR / AMR-WB（{@code #!AMR} 前缀）。 */
  public static boolean isAmrMagic(byte[] header) {
    return startsWith(header, AMR_MAGIC);
  }

  /**
   * 路径/文件名是否按后缀像 PDF（不读内容）。
   *
   * <p>SpotBugs：仅对 ASCII 扩展名做 Locale.ROOT 小写匹配。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 扩展名做 Locale.ROOT 小写匹配以判 PDF，不参与安全/身份比较")
  public static boolean hasPdfExtension(Path file) {
    if (file == null) {
      return false;
    }
    Path name = file.getFileName();
    if (name == null) {
      return false;
    }
    return name.toString().toLowerCase(Locale.ROOT).endsWith(EXT_PDF);
  }

  /** 入站落盘后：若当前扩展名为占位（{@code .bin}/{@code .file} 或空白）且内容可识别，返回更好扩展名；否则 {@code null}。 */
  public static String betterFileExtension(Path file, String currentExt) {
    if (!isPlaceholderExt(currentExt)) {
      return null;
    }
    if (isPdfMagic(file)) {
      return EXT_PDF;
    }
    if (isOggMagic(file)) {
      return EXT_OGG;
    }
    if (isSilkMagic(file)) {
      return EXT_SILK;
    }
    if (isAmrMagic(file)) {
      return EXT_AMR;
    }
    return null;
  }

  private static boolean startsWith(byte[] header, byte[] magic) {
    if (header == null || header.length < magic.length) {
      return false;
    }
    for (int i = 0; i < magic.length; i++) {
      if (header[i] != magic[i]) {
        return false;
      }
    }
    return true;
  }

  private static boolean magicMatches(Path file, int len, Predicate<byte[]> check) {
    if (file == null || !Files.isRegularFile(file)) {
      return false;
    }
    try (InputStream in = Files.newInputStream(file)) {
      return check.test(in.readNBytes(len));
    } catch (IOException e) {
      return false;
    }
  }

  /** SpotBugs：仅对 ASCII 占位扩展名做 Locale.ROOT 小写匹配。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "仅对 ASCII 扩展名 .bin/.file 做 Locale.ROOT 小写匹配，不参与安全/身份比较")
  private static boolean isPlaceholderExt(String ext) {
    if (ext == null || ext.isBlank()) {
      return true;
    }
    String lower = ext.toLowerCase(Locale.ROOT);
    return EXT_BIN.equals(lower) || EXT_FILE.equals(lower);
  }
}
