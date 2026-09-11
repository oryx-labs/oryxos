package io.oryxos.channel.weixinkf;

/** {@code /cgi-bin/media/get} 下载结果。 */
record WeixinKfMediaBlob(byte[] bytes, String contentType, String fileName) {

  WeixinKfMediaBlob {
    if (bytes == null) {
      throw new IllegalArgumentException("bytes 为空");
    }
  }
}
