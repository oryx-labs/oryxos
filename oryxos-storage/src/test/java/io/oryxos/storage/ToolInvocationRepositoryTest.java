package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 第17节 harness 补充：ToolInvocationRepositoryTest——建表必须走手工 schema.sql（同 16 节口径）。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ToolInvocationRepositoryTest {

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

  @Autowired ToolInvocationRepository repository;

  @Test
  @DisplayName("手工建表脚本_成功记录写读完整")
  void manualSchemaPersistsSuccessRecordCompletely() {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId("s-1");
    invocation.setToolName("http_get");
    invocation.setInputJson("{\"url\":\"https://wttr.in\"}");
    invocation.setResultJson("晴，28°C");
    invocation.setSuccess(true);
    invocation.setDurationMs(345L);

    repository.saveAndFlush(invocation);

    ToolInvocation loaded = repository.findBySessionId("s-1").get(0);
    assertEquals("http_get", loaded.getToolName());
    assertEquals("晴，28°C", loaded.getResultJson());
    assertTrue(loaded.isSuccess());
    assertNotNull(loaded.getCreatedAt());
  }

  @Test
  @DisplayName("失败记录_success与error_message两列真实存在且完整读回")
  void failureRecordKeepsSuccessFlagAndErrorMessage() {
    ToolInvocation invocation = new ToolInvocation();
    invocation.setSessionId("s-2");
    invocation.setToolName("shell");
    invocation.setInputJson("{\"command\":\"uptime\"}");
    invocation.setSuccess(false);
    invocation.setErrorMessage("命令不在白名单");
    invocation.setDurationMs(12L);

    repository.saveAndFlush(invocation);

    ToolInvocation loaded = repository.findBySessionId("s-2").get(0);
    assertFalse(loaded.isSuccess());
    assertEquals("命令不在白名单", loaded.getErrorMessage()); // 失败也要记，事后可查
  }

  @Test
  @DisplayName("审计实现自吞异常_不阻断工具结果返回")
  void auditorSwallowsItsOwnFailureWithoutBreakingMainPath() {
    ToolInvocationRepository broken = mock(ToolInvocationRepository.class);
    when(broken.save(any())).thenThrow(new RuntimeException("db locked"));
    JpaToolInvocationAuditor auditor = new JpaToolInvocationAuditor(broken);

    // 口径同 16 节 D5：审计自身失败只记 ERROR，不上抛
    assertDoesNotThrow(() -> auditor.record("s-3", "http_get", "{}", null, true, null, 1L));
  }
}
