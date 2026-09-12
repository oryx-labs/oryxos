package io.oryxos.channel.weixinmini;

/** 回调 XML 字段抽取（小程序加密包 / 明文消息）。 */
final class WeixinMiniCallbackXml {

  private WeixinMiniCallbackXml() {}

  static String cdataOrText(String xml, String tag) {
    if (xml == null || tag == null || tag.isBlank()) {
      return null;
    }
    String openCdata = "<" + tag + "><![CDATA[";
    int start = xml.indexOf(openCdata);
    if (start >= 0) {
      start += openCdata.length();
      int end = xml.indexOf("]]></" + tag + ">", start);
      if (end > start) {
        return xml.substring(start, end).strip();
      }
    }
    String open = "<" + tag + ">";
    start = xml.indexOf(open);
    if (start < 0) {
      return null;
    }
    start += open.length();
    int end = xml.indexOf("</" + tag + ">", start);
    if (end <= start) {
      return null;
    }
    return xml.substring(start, end).strip();
  }
}
