package io.oryxos.core.channel;

/**
 * 入站媒体附件（图片、文件、语音、视频等），由渠道 normalizer 从平台事件提取。
 *
 * @param type 媒体类型，见 {@link #TYPE_IMAGE} / {@link #TYPE_FILE} / {@link #TYPE_AUDIO} / {@link
 *     #TYPE_VIDEO}
 * @param url 可直接访问的路径或 URL（下载落地后的本地绝对路径，或企微临时 URL）；可能为空
 * @param reference 平台资源标识（飞书 file_key/image_key、钉钉 downloadCode、企微 aeskey 等）
 * @param fileName 平台原始文件名（可空）
 */
public record InboundAttachment(String type, String url, String reference, String fileName) {

  public static final String TYPE_IMAGE = "image";
  public static final String TYPE_FILE = "file";
  public static final String TYPE_AUDIO = "audio";
  public static final String TYPE_VIDEO = "video";

  /** 兼容旧三参构造（无文件名）。 */
  public InboundAttachment(String type, String url, String reference) {
    this(type, url, reference, null);
  }

  public InboundAttachment {
    requireNonBlank(type, "type");
    if (isBlank(url)) {
      if (isBlank(reference)) {
        throw new IllegalArgumentException("url 与 reference 至少提供一个");
      }
    }
    if (fileName != null && fileName.isBlank()) {
      fileName = null;
    }
  }

  public static InboundAttachment imageUrl(String url) {
    return new InboundAttachment(TYPE_IMAGE, url, null, null);
  }

  public static InboundAttachment imageReference(String reference) {
    return new InboundAttachment(TYPE_IMAGE, null, reference, null);
  }

  public static InboundAttachment fileUrl(String url) {
    return new InboundAttachment(TYPE_FILE, url, null, null);
  }

  public static InboundAttachment fileUrl(String url, String fileName) {
    return new InboundAttachment(TYPE_FILE, url, null, fileName);
  }

  public static InboundAttachment fileReference(String reference) {
    return new InboundAttachment(TYPE_FILE, null, reference, null);
  }

  public static InboundAttachment fileReference(String reference, String fileName) {
    return new InboundAttachment(TYPE_FILE, null, reference, fileName);
  }

  public static InboundAttachment audioUrl(String url) {
    return new InboundAttachment(TYPE_AUDIO, url, null, null);
  }

  public static InboundAttachment audioReference(String reference) {
    return new InboundAttachment(TYPE_AUDIO, null, reference, null);
  }

  public static InboundAttachment videoUrl(String url) {
    return new InboundAttachment(TYPE_VIDEO, url, null, null);
  }

  public static InboundAttachment videoUrl(String url, String fileName) {
    return new InboundAttachment(TYPE_VIDEO, url, null, fileName);
  }

  public static InboundAttachment videoReference(String reference) {
    return new InboundAttachment(TYPE_VIDEO, null, reference, null);
  }

  public static InboundAttachment videoReference(String reference, String fileName) {
    return new InboundAttachment(TYPE_VIDEO, null, reference, fileName);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static void requireNonBlank(String value, String field) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
  }
}
