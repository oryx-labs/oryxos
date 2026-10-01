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
      // end >= start：空 CDATA 段（<tag><![CDATA[]]></tag>，end == start）也是合法输入，
      // 必须返回空串；用 > 会落空到普通文本分支，把 "<![CDATA[]]>" 包装残骸当内容返回（#615）
      if (end >= start) {
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
