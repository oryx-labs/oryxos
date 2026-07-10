package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 课件《第16节》验收 harness：LlmCallRepositoryTest——建表必须走手工 schema.sql。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LlmCallRepositoryTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none"); // 不许 Hibernate 自动建表
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always"); // 建表走手工 schema.sql
  }

  @Autowired LlmCallRepository repository;

  @Test
  void 手工建表脚本_成功记录写读完整() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-1");
    call.setProvider("deepseek");
    call.setModel("deepseek-chat");
    call.setPromptTokens(120);
    call.setCompletionTokens(80);
    call.setTotalTokens(200);
    call.setSuccess(true);
    call.setDurationMs(1234L);

    repository.saveAndFlush(call);

    LlmCall loaded = repository.findBySessionId("s-1").get(0);
    assertEquals("deepseek", loaded.getProvider());
    assertEquals(200, loaded.getTotalTokens());
    assertTrue(loaded.isSuccess());
    assertNotNull(loaded.getCreatedAt());
  }

  @Test
  void 失败记录_errorMessage列真实存在且完整读回() {
    LlmCall call = new LlmCall();
    call.setSessionId("s-2");
    call.setProvider("kimi");
    call.setModel("moonshot-v1");
    call.setSuccess(false);
    call.setErrorMessage("connect timeout");
    call.setDurationMs(3000L);

    repository.saveAndFlush(call);

    LlmCall loaded = repository.findBySessionId("s-2").get(0);
    assertEquals(false, loaded.isSuccess());
    assertEquals("connect timeout", loaded.getErrorMessage()); // success/error_message 两列真实存在
  }

  @Test
  void 审计实现自吞异常_不阻断主链路() {
    LlmCallRepository broken = mock(LlmCallRepository.class);
    when(broken.save(any())).thenThrow(new RuntimeException("db locked"));
    JpaLlmCallAuditor auditor = new JpaLlmCallAuditor(broken);

    // research D5：审计自身失败只记 ERROR，不上抛
    assertDoesNotThrow(
        () -> auditor.record("s-3", "deepseek", "m", null, true, null, List.of().size()));
  }
}
