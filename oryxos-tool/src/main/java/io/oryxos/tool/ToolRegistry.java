package io.oryxos.tool;

import io.oryxos.core.OryxTool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbacks;

/**
 * 工具注册表：所有来源（内置 @Tool、方式三 @Tool、MCP、直接实现）统一成 {@link OryxTool} 在此汇总， ReAct 循环与 ToolExecutor
 * 由此对来源无感知（TechSol §6.6）。
 *
 * <p>两条注册路径：{@link #register}（直接实现 / MCP adapter）与 {@link #registerAnnotated} （@Tool 注解扫描——schema
 * 自动生成，宪法 II 第二件事）。重名拒绝：静默覆盖会让两个来源打架且难查。
 */
public class ToolRegistry {

  private final Map<String, OryxTool> tools = new LinkedHashMap<>();

  public void register(OryxTool tool) {
    if (tools.containsKey(tool.getName())) {
      throw new IllegalStateException("工具重名，拒绝注册: " + tool.getName());
    }
    tools.put(tool.getName(), tool);
  }

  /**
   * @Tool 注解方法扫描注册：schema 由 Spring AI 自动生成，逐方法包装为 OryxTool。
   */
  public void registerAnnotated(Object bean) {
    for (ToolCallback callback : ToolCallbacks.from(bean)) {
      register(new AnnotatedToolAdapter(callback));
    }
  }

  public boolean contains(String name) {
    return tools.containsKey(name);
  }

  public Optional<OryxTool> get(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public Collection<OryxTool> all() {
    return List.copyOf(tools.values());
  }

  /** 供 OryxOsRuntime 的 tools() bean——ToolExecutor/PromptBuilder 消费的既有形态。 */
  public Map<String, OryxTool> asMap() {
    return Map.copyOf(tools);
  }

  /** 按 Profile 的 tools 字段过滤：结果 = 声明列表中存在于注册表的项，不多不少；未知名跳过。 */
  public List<OryxTool> filterByNames(List<String> names) {
    List<OryxTool> filtered = new ArrayList<>();
    for (String name : names) {
      OryxTool tool = tools.get(name);
      if (tool != null) {
        filtered.add(tool);
      }
    }
    return filtered;
  }
}
