package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

/** 课件《第18节》验收 harness：SessionManagerTest——会话口径（幂等/隔离/id 单点）在此钉死。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionManagerTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteFile(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("manager-test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always");
  }

  @Autowired private SessionRepository repository;

  private JpaSessionManager manager() {
    return new JpaSessionManager(repository);
  }

  @Test
  @DisplayName("同一三元组_历次getOrCreate都是同一个Session")
  void sameTriple_everyGetOrCreateReturnsSameSession() {
    JpaSessionManager sessionManager = manager();

    var first = sessionManager.getOrCreate("cli", "wang", "default");
    var second = sessionManager.getOrCreate("cli", "wang", "default");
    assertEquals(first.sessionId(), second.sessionId()); // 幂等：多轮对话靠它串起来

    var other = sessionManager.getOrCreate("web", "wang", "default");
    assertNotEquals(first.sessionId(), other.sessionId()); // channel 不同就是不同会话
  }

  @Test
  @DisplayName("三元组任一元素不同_都是不同会话")
  void anyDifferentTripleElement_createsDifferentSession() {
    JpaSessionManager sessionManager = manager();
    var base = sessionManager.getOrCreate("cli", "wang", "default");

    assertNotEquals(
        base.sessionId(), sessionManager.getOrCreate("cli", "li", "default").sessionId());
    assertNotEquals(
        base.sessionId(), sessionManager.getOrCreate("cli", "wang", "weather").sessionId());
  }

  @Test
  @DisplayName("id 格式为 channel:user:profile（拼接只在 SessionManager 一处）")
  void sessionIdFollowsTripleFormat() {
    var session = manager().getOrCreate("cli", "wang", "default");

    assertEquals("cli:wang:default", session.sessionId());
  }

  @Test
  @DisplayName("getOrCreate 未命中时落一条 active 记录")
  void getOrCreateMissPersistsActiveRecord() {
    manager().getOrCreate("cli", "zhao", "default");

    Session entity = repository.findById("cli:zhao:default").orElseThrow();
    assertEquals("active", entity.getStatus());
    assertEquals("cli", entity.getChannel());
    assertEquals("zhao", entity.getUserId());
    assertEquals("default", entity.getProfileName());
    assertTrue(entity.getCreatedAt() != null);
  }

  @Test
  @DisplayName("archive 把会话置为 archived 并记 archived_at（26 节接线）")
  void archiveMarksSessionArchived() {
    JpaSessionManager manager = manager();
    manager.getOrCreate("web", "li", "default");

    boolean archived = manager.archive("web:li:default");

    assertTrue(archived);
    Session entity = repository.findById("web:li:default").orElseThrow();
    assertEquals("archived", entity.getStatus());
    assertTrue(entity.getArchivedAt() != null, "归档时间应被记下");
  }

  @Test
  @DisplayName("archive 不存在的会话_返回 false 不报错")
  void archiveMissingReturnsFalse() {
    assertFalse(manager().archive("web:nobody:default"));
  }
}
