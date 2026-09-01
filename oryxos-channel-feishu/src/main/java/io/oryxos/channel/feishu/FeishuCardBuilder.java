package io.oryxos.channel.feishu;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书交互式卡片构建器：为流式回复构建动态更新的卡片消息。
 *
 * <p>卡片分两种状态：
 *
 * <ul>
 *   <li>处理中：蓝色头部 + 思考过程 + 工具调用状态（实时更新）
 *   <li>已完成：绿色头部 + 最终回答（固定内容）
 * </ul>
 *
 * <p>卡片 JSON 遵循飞书「消息卡片搭建工具」格式，文档： https://open.feishu.cn/document/ukTMukTMukTM/uczM3QjL3MzN04yNzcDN
 */
public class FeishuCardBuilder {

  private static final String HEADER_COLOR_PROCESSING = "blue";
  private static final String HEADER_COLOR_COMPLETED = "green";
  private static final String HEADER_COLOR_ERROR = "red";

  private static final String ICON_THINKING = "🤔";
  private static final String ICON_TOOL = "🔧";
  private static final String ICON_SUCCESS = "✅";
  private static final String ICON_ERROR = "❌";

  private static final int MAX_THINKING_LINES = 5; // 思考过程最多保留最近 N 行

  /**
   * 构建"处理中"状态的卡片。
   *
   * @param thinkingProcess 思考过程文本列表（最近的在前）
   * @param activeTools 当前活跃的工具调用列表
   * @param completedTools 已完成的工具调用列表
   * @return 卡片 JSON 字符串
   */
  public String buildProcessingCard(
      List<String> thinkingProcess, List<String> activeTools, List<String> completedTools) {
    JsonObject card = new JsonObject();
    card.add("config", buildConfig());
    card.add("header", buildHeader(ICON_THINKING + " 正在思考...", HEADER_COLOR_PROCESSING));

    JsonArray elements = new JsonArray();

    // 思考过程（限制行数避免卡片过长）
    if (!thinkingProcess.isEmpty()) {
      StringBuilder thinking = new StringBuilder("**思考过程：**\n");
      int lines = Math.min(thinkingProcess.size(), MAX_THINKING_LINES);
      for (int i = 0; i < lines; i++) {
        thinking.append(thinkingProcess.get(i)).append("\n");
      }
      if (thinkingProcess.size() > MAX_THINKING_LINES) {
        thinking.append("...\n");
      }
      elements.add(buildMarkdownElement(thinking.toString().trim()));
    }

    // 工具调用状态
    if (!activeTools.isEmpty() || !completedTools.isEmpty()) {
      StringBuilder tools = new StringBuilder("**工具调用：**\n");
      for (String tool : activeTools) {
        tools.append(ICON_TOOL).append(" 正在执行：").append(tool).append("\n");
      }
      for (String tool : completedTools) {
        tools.append(ICON_SUCCESS).append(" ").append(tool).append(" 完成\n");
      }
      elements.add(buildMarkdownElement(tools.toString().trim()));
    }

    card.add("elements", elements);
    return card.toString();
  }

  /**
   * 构建"已完成"状态的卡片。
   *
   * @param finalAnswer 最终回答内容
   * @return 卡片 JSON 字符串
   */
  public String buildCompletedCard(String finalAnswer) {
    JsonObject card = new JsonObject();
    card.add("config", buildConfig());
    card.add("header", buildHeader(ICON_SUCCESS + " 回答", HEADER_COLOR_COMPLETED));

    JsonArray elements = new JsonArray();
    elements.add(buildMarkdownElement(finalAnswer));

    card.add("elements", elements);
    return card.toString();
  }

  /**
   * 构建"错误"状态的卡片。
   *
   * @param errorMessage 可读的错误信息
   * @return 卡片 JSON 字符串
   */
  public String buildErrorCard(String errorMessage) {
    JsonObject card = new JsonObject();
    card.add("config", buildConfig());
    card.add("header", buildHeader(ICON_ERROR + " 处理失败", HEADER_COLOR_ERROR));

    JsonArray elements = new JsonArray();
    elements.add(buildMarkdownElement("抱歉，处理失败了：\n\n" + errorMessage));

    card.add("elements", elements);
    return card.toString();
  }

  /**
   * 构建空白"处理中"卡片（初始状态）。
   *
   * @return 卡片 JSON 字符串
   */
  public String buildInitialCard() {
    return buildProcessingCard(List.of("正在分析你的问题..."), new ArrayList<>(), new ArrayList<>());
  }

  private JsonObject buildConfig() {
    JsonObject config = new JsonObject();
    config.addProperty("wide_screen_mode", true);
    return config;
  }

  private JsonObject buildHeader(String title, String color) {
    JsonObject header = new JsonObject();
    header.addProperty("template", color);

    JsonObject titleObj = new JsonObject();
    titleObj.addProperty("tag", "plain_text");
    titleObj.addProperty("content", title);
    header.add("title", titleObj);

    return header;
  }

  private JsonObject buildMarkdownElement(String content) {
    JsonObject element = new JsonObject();
    element.addProperty("tag", "markdown");
    element.addProperty("content", content);
    return element;
  }
}
