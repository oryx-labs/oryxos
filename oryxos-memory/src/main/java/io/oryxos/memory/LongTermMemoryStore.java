package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import java.util.List;

/**
 * 长期记忆的可插拔后端接口（第 21 节评审那道"接口墙"的对下一侧）。三档实现（Markdown / SQLite / Mem0） 各写各的存储，但共守四条行为契约：
 *
 * <ol>
 *   <li>不缓存：{@link #load} 每次重新读文件/查库/调 API，写完立刻可见；
 *   <li>核心记忆永不被截断：截断只作用在归档区，核心区完整返回；
 *   <li>写核心还是写归档由调用方经 scope 显式指定，系统不猜；
 *   <li>{@link #recallByKeyword} 只在归档区做简单关键词匹配。
 * </ol>
 */
public interface LongTermMemoryStore {

  void append(String content, MemoryScope scope);

  /** 核心区全量 + 归档区（截断后）。 */
  String load();

  /** 只在归档区检索（核心区本就全量注入、不参与检索）。 */
  List<String> recallByKeyword(String keyword);
}
