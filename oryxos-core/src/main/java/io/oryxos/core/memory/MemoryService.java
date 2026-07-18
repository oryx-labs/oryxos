package io.oryxos.core.memory;

import io.oryxos.core.session.Session;
import java.util.List;

/**
 * 记忆统一门面（跨模块契约，宪法 v1.1.0 放 core）：上层（PromptBuilder / MemoryTools）只认这三个方法，
 * 不感知底层长期记忆是文件、本地库还是外部服务——这是第 21 节评审那道"接口墙"的对上一侧。
 *
 * <p>接口上移 core 而非留在 oryxos-memory，是因为 PromptBuilder（core）必须注入它，而 memory→core 依赖已存在，接口留 memory 会成环（同
 * 16 节 ProviderService 上移）。实现在 oryxos-memory。
 */
public interface MemoryService {

  /** 拼进 Prompt 的记忆内容：长期记忆（核心区全量 + 归档区截断后）+ 会话历史。 */
  String buildContext(Session session);

  /** save_memory 转发：记一条到指定分区。 */
  void remember(String content, MemoryScope scope);

  /** recall_memory 转发：按关键词只在归档区检索。 */
  List<String> recall(String keyword);

  /**
   * 返回某个 Agent 的长期记忆全文（核心 + 归档）。30 节 GET /api/v1/agents/&lt;name&gt;/memory 接线，委托后端 store 的
   * load（无缓存）。agentName 圈定作用域——记忆现在跟着 Agent 走，不再全局。
   */
  String readAll(String agentName);
}
