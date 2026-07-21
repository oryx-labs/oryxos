package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.ToolResult;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.session.Message;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 课件《第18节》验收 harness：SessionRepositoryTest——手工表能存能读、历史回读完整、跨重启恢复。 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SessionRepositoryTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteFile(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("repo-test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always");
  }

  @Autowired private SessionRepository repository;

  @Test
  @DisplayName("手工建表脚本建出的 sessions 表能存能读")
  void manualSchemaTableSupportsSaveAndRead() {
    Session entity = new Session();
    entity.setSessionId("cli:wang:default");
    entity.setProfileName("default");
    entity.setChannel("cli");
    entity.setUserId("wang");
    entity.setStatus("active");
    repository.save(entity);

    Session found = repository.findById("cli:wang:default").orElseThrow();
    assertEquals("default", found.getProfileName());
    assertNotNull(found.getCreatedAt()); // @PrePersist 生效——表由手工脚本建出而非 Hibernate
  }

  @Test
  @DisplayName("messages_json 序列化回读后消息完整（三类角色、顺序不变）")
  void messagesJsonRoundTripKeepsAllMessagesInOrder() {
    JpaSessionManager manager = new JpaSessionManager(repository);
    var session = manager.getOrCreate("cli", "wang", "default");
    session.appendUser("今天天气怎么样");
    session.appendAssistant(new ProviderResponse("我查一下", List.of(), null));
    session.appendToolResult(
        new io.oryxos.core.provider.ToolCallRequest("http_get", "{}"), ToolResult.ok("晴，28°C"));
    manager.save(session);

    var reloaded = manager.getOrCreate("cli", "wang", "default");

    List<Message> messages = reloaded.messages();
    assertEquals(3, messages.size());
    assertEquals(
        List.of("user", "assistant", "tool"), // 顺序不变量：按发生序回放
        messages.stream().map(Message::role).toList());
    assertEquals("今天天气怎么样", messages.get(0).content());
    assertEquals("http_get", messages.get(2).toolName());
  }

  @Test
  @DisplayName("模拟重启（新建 Manager 重查同一库）历史还在")
  void historySurvivesSimulatedRestart() {
    JpaSessionManager before = new JpaSessionManager(repository);
    var session = before.getOrCreate("cli", "li", "default");
    session.appendUser("记住我喜欢咖啡");
    before.save(session);

    // 模拟重启：全新 Manager 实例（新 ObjectMapper），同一库文件重查
    JpaSessionManager after = new JpaSessionManager(repository);
    var restored = after.getOrCreate("cli", "li", "default");

    assertEquals(1, restored.messages().size());
    assertEquals("记住我喜欢咖啡", restored.messages().get(0).content());
  }

  @Test
  @DisplayName("零消息的新会话正常保存与恢复")
  void emptySessionSavesAndRestores() {
    JpaSessionManager manager = new JpaSessionManager(repository);
    var session = manager.getOrCreate("cli", "empty", "default");
    manager.save(session);

    assertTrue(manager.getOrCreate("cli", "empty", "default").messages().isEmpty());
  }

  @Test
  @DisplayName("save 刷新 last_active_at")
  void saveRefreshesLastActiveAt() {
    JpaSessionManager manager = new JpaSessionManager(repository);
    var session = manager.getOrCreate("cli", "wang", "weather");
    Instant beforeSave = Instant.now();
    manager.save(session);

    Session entity = repository.findById("cli:wang:weather").orElseThrow();
    assertNotNull(entity.getLastActiveAt());
    assertTrue(!entity.getLastActiveAt().isBefore(beforeSave.minusSeconds(5)));
  }
}
