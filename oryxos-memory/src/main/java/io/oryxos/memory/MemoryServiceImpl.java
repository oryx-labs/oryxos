package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import java.util.List;

/**
 * MemoryService 门面实现：把长期记忆读写委托给可插拔的 {@link LongTermMemoryStore}——换后端只换注入的 store， 门面签名与上层调用不变。
 *
 * <p>buildContext 返回长期记忆（核心区全量 + 归档区截断后，由 store.load 保证契约二）；会话历史由 PromptBuilder 的会话历史段独立负责，两者一起注入
 * system prompt（FR6）。session 参数保留供未来按 Agent/用户圈定记忆作用域（Mem0 档已按 userId 组织），当前实现按全局长期记忆。
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
    return store.load();
  }

  @Override
  public void remember(String content, MemoryScope scope) {
    store.append(content, scope);
  }

  @Override
  public List<String> recall(String keyword) {
    return store.recallByKeyword(keyword);
  }
}
