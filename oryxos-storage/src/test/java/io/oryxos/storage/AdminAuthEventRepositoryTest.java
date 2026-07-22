package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.core.auth.AdminAuthEventType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminAuthEventRepositoryTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("auth-test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always");
  }

  @Autowired AdminAuthEventRepository repository;

  @Test
  void persistsAuthEventsFromManualSchemaAndQueriesNewestFirst() {
    AdminAuthEvent older =
        new AdminAuthEvent(
            "admin",
            AdminAuthEventType.LOGIN_FAILED,
            Instant.parse("2026-07-22T09:00:00Z"),
            "127.0.0.1",
            "JUnit",
            null);
    AdminAuthEvent newer =
        new AdminAuthEvent(
            "admin",
            AdminAuthEventType.LOGIN_SUCCEEDED,
            Instant.parse("2026-07-22T09:01:00Z"),
            "127.0.0.1",
            "JUnit",
            "session-1");

    new JpaAdminAuthAuditStore(repository).record(older);
    new JpaAdminAuthAuditStore(repository).record(newer);

    List<AdminAuthEventEntity> events =
        repository.findByOrderByCreatedAtDesc(PageRequest.of(0, 10));

    assertEquals(2, events.size());
    assertEquals(AdminAuthEventType.LOGIN_SUCCEEDED.name(), events.get(0).getEventType());
    assertEquals("admin", events.get(0).getPrincipal());
    assertEquals("session-1", events.get(0).getSessionId());
    assertEquals(AdminAuthEventType.LOGIN_FAILED.name(), events.get(1).getEventType());
    assertNotNull(events.get(0).getCreatedAt());
  }
}
