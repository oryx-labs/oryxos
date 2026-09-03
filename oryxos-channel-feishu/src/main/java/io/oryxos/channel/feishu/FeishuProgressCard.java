package io.oryxos.channel.feishu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 飞书交互卡片 JSON（header 模板色 + lark_md 正文），供 create/patch。 */
final class FeishuProgressCard {

  static final String TEMPLATE_BLUE = "blue";
  static final String TEMPLATE_GREEN = "green";
  static final String TEMPLATE_RED = "red";

  private static final String TAG_PLAIN = "plain_text";
  private static final String TAG_DIV = "div";
  private static final String TAG_LARK_MD = "lark_md";

  private FeishuProgressCard() {}

  static String build(String title, String template, String markdownBody) {
    JsonObject titleObj = new JsonObject();
    titleObj.addProperty("tag", TAG_PLAIN);
    titleObj.addProperty("content", title == null ? "" : title);

    JsonObject header = new JsonObject();
    header.add("title", titleObj);
    header.addProperty("template", template == null ? TEMPLATE_BLUE : template);

    JsonObject md = new JsonObject();
    md.addProperty("tag", TAG_LARK_MD);
    md.addProperty("content", markdownBody == null ? "" : markdownBody);

    JsonObject div = new JsonObject();
    div.addProperty("tag", TAG_DIV);
    div.add("text", md);

    JsonArray elements = new JsonArray();
    elements.add(div);

    JsonObject card = new JsonObject();
    card.add("header", header);
    card.add("elements", elements);
    return card.toString();
  }
}
