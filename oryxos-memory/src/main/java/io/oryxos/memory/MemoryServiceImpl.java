package io.oryxos.memory;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import java.util.List;
import java.util.function.Supplier;

/**
 * MemoryService 门面实现：把长期记忆读写委托给可插拔的 {@link LongTermMemoryStore}——换后端只换注入的 store， 门面签名与上层调用不变。
 *
 * <p>buildContext 返回长期记忆（核心区全量 + 归档区截断后，由 store.load 保证契约二）；会话历史由 PromptBuilder 的会话历史段独立负责，两者一起注入
 * system prompt（FR6）。
 *
 * <p>Agent 专属记忆（30 节）：记忆作用域跟着 Agent 走。写路径（remember/recall）经 save_memory/recall_memory 工具调用，Agent
 * 名已由 {@link ToolExecutionContext}（ToolExecutor 置入）就位，门面直接透传给 store 即可；读路径（buildContext/readAll）不经
 * ToolExecutor，门面在委托 store.load 前后临时置入 Agent 名（buildContext 取 session.profileName()、readAll
 * 取入参），读完复原。
 */
public class MemoryServiceImpl implements MemoryService {

  private final LongTermMemoryStore store;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "门面必须持有注入的后端 store 引用（组合模式的协作者）；生命周期由装配方管理")
  public MemoryServiceImpl(LongTermMemoryStore store) {
    this.store = store;
  }

  @Override
  public String buildContext(Session session) {
    return withAgent(session.profileName(), store::load);
  }

  @Override
  public void remember(String content, MemoryScope scope) {
    store.append(content, scope);
  }

  @Override
  public List<String> recall(String keyword) {
    return store.recallByKeyword(keyword);
  }

  @Override
  public String readAll(String agentName) {
    return withAgent(agentName, store::load);
  }

  /** 临时置入 Agent 名跑一段读操作，结束后复原上一层上下文（读路径不经 ToolExecutor，需自行圈定作用域）。 */
  private static <T> T withAgent(String agentName, Supplier<T> body) {
    String previous = ToolExecutionContext.agentName();
    ToolExecutionContext.setAgentName(agentName);
    try {
      return body.get();
    } finally {
      if (previous == null) {
        ToolExecutionContext.clear();
      } else {
        ToolExecutionContext.setAgentName(previous);
      }
    }
  }
}
