package io.oryxos.memory.builtin;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置记忆工具（20 节预留的 save_memory / recall_memory 在此落地）：把长期记忆暴露给 Agent。
 *
 * <p>只认 {@link MemoryService} 门面，对底层是哪档后端完全无感——换后端不碰本类。写核心还是写归档由 Agent 经 scope 显式指定（契约三），缺省归档。
 */
public class MemoryTools {

  private final MemoryService memoryService;

  public MemoryTools(MemoryService memoryService) {
    this.memoryService = memoryService;
  }

  @Tool(name = "save_memory", description = "记住一件值得长期记住的事")
  public String saveMemory(
      @ToolParam(description = "要记住的内容") String content,
      @ToolParam(description = "core 或 archival，不确定就填 archival") String scope) {
    String normalized =
        (scope == null || scope.isBlank()) ? "ARCHIVAL" : scope.toUpperCase(Locale.ROOT);
    MemoryScope target;
    try {
      target = MemoryScope.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      // 非法 scope 明确报错点名，不静默落错区
      return "无法识别的记忆分区: " + scope + "（应为 core 或 archival）";
    }
    memoryService.remember(content, target);
    return "已记住";
  }

  @Tool(name = "recall_memory", description = "按关键词检索长期记忆")
  public String recallMemory(@ToolParam(description = "检索关键词") String keyword) {
    List<String> hits = memoryService.recall(keyword);
    return hits.isEmpty() ? "没有找到相关记忆" : String.join("\n", hits);
  }
}
