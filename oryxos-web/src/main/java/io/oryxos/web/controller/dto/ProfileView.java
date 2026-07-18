package io.oryxos.web.controller.dto;

import java.util.List;

/** GET /profiles 视图：从 Profile 投影出可对外展示的字段（不含敏感/内部结构）。 */
public record ProfileView(
    String name, String description, String provider, String model, List<String> tools) {

  public ProfileView {
    tools = tools == null ? List.of() : List.copyOf(tools);
  }
}
