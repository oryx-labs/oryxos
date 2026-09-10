package io.oryxos.channel.weixinkf;

/** 回调 XML 字段抽取（企微加密包 / 明文事件）。 */
final class WeixinKfCallbackXml {

  private WeixinKfCallbackXml() {}

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
