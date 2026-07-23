package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 31 节默认工具库：current_time / json_extract（纯计算原语）。 */
class UtilToolsTest {

  private final UtilTools tools = new UtilTools();

  @Test
  @DisplayName("current_time 指定时区返回可读日期时间")
  void currentTimeWithZone() {
    String t = tools.currentTime("Asia/Shanghai");
    assertNotNull(t);
    assertTrue(t.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*"), t);
  }

  @Test
  @DisplayName("current_time 留空用系统时区，不报错")
  void currentTimeDefaultZone() {
    assertNotNull(tools.currentTime(null));
  }

  @Test
  @DisplayName("json_extract 按点号 + 下标取到嵌套值")
  void jsonExtractNested() {
    String json = "{\"data\":{\"items\":[{\"name\":\"oryx\"},{\"name\":\"os\"}]}}";
    assertEquals("oryx", tools.jsonExtract(json, "data.items[0].name"));
    assertEquals("os", tools.jsonExtract(json, "data.items[1].name"));
  }

  @Test
  @DisplayName("json_extract 路径不存在给可读提示")
  void jsonExtractMissing() {
    assertTrue(tools.jsonExtract("{\"a\":1}", "a.b.c").contains("未找到"));
  }

  @Test
  @DisplayName("json_extract 非法 JSON 抛 IllegalArgumentException")
  void jsonExtractBadJson() {
    assertThrows(IllegalArgumentException.class, () -> tools.jsonExtract("not json", "a"));
  }
}
