package io.oryxos.web.controller;

import io.oryxos.core.OryxTool;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.ToolView;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读：列出注册表里所有工具。注入运行时装配的 {@code tools} Map bean（@Qualifier 指名，避免与"按类型收集所有 OryxTool
 * bean"的自动装配歧义）；OryxTool 是 core 类型，web 无需依赖 oryxos-tool 模块。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "SPRING_ENDPOINT",
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase")
@RestController
@RequestMapping("/api/v1/tools")
public class ToolApiController {

  private final Map<String, OryxTool> tools;

  public ToolApiController(@Qualifier("tools") Map<String, OryxTool> tools) {
    this.tools = Map.copyOf(tools);
  }

  @GetMapping
  public ApiResponse<List<ToolView>> list() {
    List<ToolView> views =
        tools.values().stream().map(t -> new ToolView(t.getName(), t.getDescription())).toList();
    return ApiResponse.ok(views);
  }
}
