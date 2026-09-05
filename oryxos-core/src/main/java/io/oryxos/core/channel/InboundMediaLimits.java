package io.oryxos.core.channel;

/** 入站媒体大小与嗅探常量（三渠 Resolver / Whisper 共用）。 */
public final class InboundMediaLimits {

  /** 单文件下载上限（字节）。 */
  public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

  /** 魔数嗅探读取上限（避免整文件进内存）。 */
  public static final int HEADER_SNIFF_BYTES = 64;

  /** Whisper 上传 wav/音频上限（抽轨后）。 */
  public static final long MAX_ASR_UPLOAD_BYTES = 25L * 1024 * 1024;

  /** 视频抽轨最长秒数（ffmpeg -t）。 */
  public static final int MAX_VIDEO_ASR_SECONDS = 120;

  private InboundMediaLimits() {}
}
