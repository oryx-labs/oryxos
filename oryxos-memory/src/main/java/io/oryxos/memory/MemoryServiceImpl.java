package io.oryxos.memory;

import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryEntryView;
import io.oryxos.core.memory.MemoryRecallCapability;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import io.oryxos.storage.MemoryEntry;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>检索路由（015 FR-009）：recall 按 {@code store.capabilities()} 分流——DELEGATED（后端自带语义）直通
 * recallByKeyword；其余走 {@link MemoryRecallEngine} 三路加权融合。写路径落库优先（FR-005）：append 成功后 仅 archival
 * 条目异步入队 {@link MemoryVectorIndex}（core 不参与检索、不入索引），索引侧任何异常不影响写入。
 */
public class MemoryServiceImpl implements MemoryService {

  private static final Logger log = LoggerFactory.getLogger(MemoryServiceImpl.class);

  private final LongTermMemoryStore store;
  private final MemoryRecallEngine engine; // null = 无三路引擎：recall 直通关键词（旧行为）
  private final MemoryVectorIndex index; // null = 未配置向量化：不建索引

  /** 旧装配形态：纯关键词检索、不建索引（未配置向量化的运行时与既有测试走这里，行为与升级前一致）。 */
  public MemoryServiceImpl(LongTermMemoryStore store) {
    this(store, null, null);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "门面必须持有注入的后端 store/引擎/索引引用（组合模式的协作者）；生命周期由装配方管理")
  public MemoryServiceImpl(
      LongTermMemoryStore store, MemoryRecallEngine engine, MemoryVectorIndex index) {
    this.store = store;
    this.engine = engine;
    this.index = index;
  }

  @Override
  public String buildContext(Session session) {
    return withAgent(session.profileName(), store::load);
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的异常消息已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  public void remember(String content, MemoryScope scope) {
    MemoryEntryView written = store.append(content, scope);
    if (scope != MemoryScope.ARCHIVAL
        || index == null
        || store.capabilities() != MemoryRecallCapability.HYBRID_BUILTIN) {
      return; // core 不入索引（FR-005）；DELEGATED 档索引归外部服务
    }
    try {
      // 用 append 返回值入队——勿再 archivalEntries().getLast()（并发会话会错绑/漏记）
      index.enqueue(currentAgent(), written);
    } catch (RuntimeException e) {
      log.warn("记忆索引入队失败（本体已落库，随对账补齐）: {}", sanitize(e.getMessage()));
    }
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  @Override
  public List<String> recall(String keyword) {
    if (engine == null || store.capabilities() == MemoryRecallCapability.DELEGATED) {
      return store.recallByKeyword(keyword);
    }
    return engine.recall(store, currentAgent(), keyword);
  }

  @Override
  public String readAll(String agentName) {
    return withAgent(agentName, store::load);
  }

  /** 启动对账入口（FR-007，T025 对每个已知 Agent + 全局各调一次）；未配置向量化或 DELEGATED 档为 no-op。 */
  public void reconcileIndex(String agentName) {
    if (index == null || store.capabilities() != MemoryRecallCapability.HYBRID_BUILTIN) {
      return;
    }
    withAgent(
        agentName,
        () -> {
          index.reconcile(agentName, store.archivalEntries());
          return null;
        });
  }

  private static String currentAgent() {
    String agent = ToolExecutionContext.agentName();
    return agent == null || agent.isBlank() ? MemoryEntry.GLOBAL_AGENT : agent;
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
