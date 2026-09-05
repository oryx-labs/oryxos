package io.oryxos.core.agent;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 进程内 Run 事件订阅：SSE 等待新事件，不作为业务真相源。 */
public class AgentRunEventHub {

  private final ConcurrentHashMap<Long, List<Consumer<AgentRunEvent>>> listeners =
      new ConcurrentHashMap<>();

  public AutoCloseable subscribe(long runId, Consumer<AgentRunEvent> listener) {
    listeners.computeIfAbsent(runId, id -> new CopyOnWriteArrayList<>()).add(listener);
    return () -> unsubscribe(runId, listener);
  }

  public void emit(AgentRunEvent event) {
    if (event == null) {
      return;
    }
    List<Consumer<AgentRunEvent>> subscribers = listeners.get(event.runId());
    if (subscribers == null) {
      return;
    }
    for (Consumer<AgentRunEvent> listener : subscribers) {
      try {
        listener.accept(event);
      } catch (RuntimeException ignored) {
        // 单个订阅者失败不影响其他连接
      }
    }
  }

  private void unsubscribe(long runId, Consumer<AgentRunEvent> listener) {
    List<Consumer<AgentRunEvent>> subscribers = listeners.get(runId);
    if (subscribers == null) {
      return;
    }
    subscribers.remove(listener);
    if (subscribers.isEmpty()) {
      listeners.remove(runId, subscribers);
    }
  }
}
