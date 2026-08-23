package io.oryxos.memory;

import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import java.util.List;

/**
 * 长期记忆的可插拔后端接口（第 21 节评审那道"接口墙"的对下一侧）。三档实现（Markdown / SQLite / Mem0） 各写各的存储，但共守四条行为契约（015 修订）：
 *
 * <ol>
 *   <li>不缓存：{@link #load} 每次重新读文件/查库/调 API，写完立刻可见；
 *   <li>核心记忆永不被截断：截断只作用在归档区，核心区完整返回、不参与检索、不入向量索引；
 *   <li>写核心还是写归档由调用方经 scope 显式指定，系统不猜；分区语义为必选能力——无法兑现的实现在装配期被可读拒绝；
 *   <li>检索只在归档区，形态按 {@link #capabilities()} 三路可降级：语义路缺席时回落关键词 + 时间两路， {@link #recallByKeyword}
 *       即统一后（跨档不区分大小写，FR-002）的关键词行为底线。
 * </ol>
 */
public interface LongTermMemoryStore {

  /** 追加一条记忆；返回刚写入的条目视图（供索引入队，避免并发下再读 {@link #archivalEntries()}{@code .getLast()} 错绑）。 */
  MemoryEntryView append(String content, MemoryScope scope);

  /** 核心区全量 + 归档区（截断后）。 */
  String load();

  /** 只在归档区做关键词匹配（015 FR-002：跨档统一不区分大小写）；返回条目原文行。 */
  List<String> recallByKeyword(String keyword);

  /** 检索能力三态（015 FR-009）——门面按此路由 recall。 */
  MemoryRecallCapability capabilities();

  /** 归档区全量条目（含时间，未截断）——时间新近路与索引对账的取数口；按写入顺序（最旧在前）。 DELEGATED 档返回空列表（引擎不会调它）。 */
  List<MemoryEntryView> archivalEntries();
}
