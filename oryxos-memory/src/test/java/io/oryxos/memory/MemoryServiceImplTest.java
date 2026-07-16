package io.oryxos.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** MemoryServiceImpl 门面：buildContext 返回长期记忆（核心全量+归档截断），remember/recall 转发。 */
class MemoryServiceImplTest {

  private MemoryService service() {
    return new MemoryServiceImpl(new InMemoryMemoryStore());
  }

  @Test
  @DisplayName("buildContext返回长期记忆_核心记忆完整在内")
  void buildContextReturnsLongTermMemoryWithCoreIntact() {
    MemoryService service = service();
    service.remember("用户叫小王", MemoryScope.CORE);
    service.remember("归档一条", MemoryScope.ARCHIVAL);

    String context = service.buildContext(new Session("cli:wang:default", "default"));

    assertTrue(context.contains("用户叫小王"), "核心记忆完整在内");
    assertTrue(context.contains("归档一条"), "归档截断后的部分也在");
  }

  @Test
  @DisplayName("remember/recall 转发给底层 store")
  void rememberAndRecallDelegateToStore() {
    MemoryService service = service();
    service.remember("项目叫 OryxOS", MemoryScope.ARCHIVAL);

    assertFalse(service.recall("OryxOS").isEmpty());
    assertTrue(service.recall("不存在的词").isEmpty());
  }

  @Test
  @DisplayName("readAll 返回长期记忆全文（委托 store.load）")
  void readAllReturnsFullMemory() {
    MemoryService service = service();
    service.remember("核心偏好", MemoryScope.CORE);
    service.remember("归档条目", MemoryScope.ARCHIVAL);

    String all = service.readAll();

    assertTrue(all.contains("核心偏好"));
    assertTrue(all.contains("归档条目"));
  }
}
