package io.oryxos.storage;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** llm_calls 的写入通道；核心阶段只写不查，按 session 查询仅供测试与后续扩展。 */
public interface LlmCallRepository extends JpaRepository<LlmCall, Long> {

  List<LlmCall> findBySessionId(String sessionId);
}
