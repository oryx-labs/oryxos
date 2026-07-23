package io.oryxos.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 通用小工具（无副作用、无沙箱）：current_time（当前时间）、json_extract（从 JSON 文本按路径取值）。 高频且用 MCP 反而别扭，作为默认内置原语。 */
public class UtilTools {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final DateTimeFormatter TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX");
  // 路径 token：字段名（非 . [ ] 的一段）或数组下标 [n]
  private static final Pattern PATH_TOKEN = Pattern.compile("[^.\\[\\]]+|\\[(\\d+)\\]");

  @Tool(
      name = "current_time",
      description = "返回当前日期时间（可指定 IANA 时区，默认系统时区），格式 yyyy-MM-dd HH:mm:ss 带时区偏移")
  public String currentTime(
      @ToolParam(required = false, description = "IANA 时区，如 Asia/Shanghai；留空用系统时区")
          String timezone) {
    ZoneId zone =
        (timezone == null || timezone.isBlank())
            ? ZoneId.systemDefault()
            : ZoneId.of(timezone.strip());
    return ZonedDateTime.now(zone).format(TIME_FORMAT);
  }

  @Tool(
      name = "json_extract",
      description = "从一段 JSON 文本里按路径取值。路径用点号 + 下标，如 data.items[0].name；返回该值的文本")
  public String jsonExtract(
      @ToolParam(description = "JSON 文本") String json,
      @ToolParam(description = "取值路径，如 a.b[0].c") String path) {
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("JSON 解析失败: " + e.getOriginalMessage());
    }
    JsonNode current = node;
    Matcher m = PATH_TOKEN.matcher(path == null ? "" : path);
    while (m.find() && current != null && !current.isMissingNode()) {
      String index = m.group(1);
      current = index != null ? current.path(Integer.parseInt(index)) : current.path(m.group());
    }
    if (current == null || current.isMissingNode() || current.isNull()) {
      return "(未找到路径: " + path + ")";
    }
    return current.isValueNode() ? current.asText() : current.toString();
  }
}
