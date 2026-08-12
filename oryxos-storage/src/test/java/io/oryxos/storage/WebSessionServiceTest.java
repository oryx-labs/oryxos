package io.oryxos.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 012-web-auth US3 验收 harness：WebSessionServiceTest——session 创建/查有效/过期惰性清/登出 钉死。 镜像
 * WebUserServiceTest 的 @DataJpaTest + @DynamicPropertySource 模式。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class WebSessionServiceTest {

  @TempDir static Path dbDir;

  @DynamicPropertySource
  static void sqliteProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("websession-test.db"));
    registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    registry.add(
        "spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
    registry.add("spring.sql.init.mode", () -> "always");
  }

  @Autowired private WebSessionRepository repository;

  private WebSessionService service() {
    return new WebSessionService(repository, Duration.ofHours(12));
  }

  @Test
  @DisplayName("create_生成UUID_sessionId且expires_at为now+12h")
  void create_generatesUuidAndExpiry() {
    WebSessionService svc = service();
    WebSession s = svc.create("admin");

    assertTrue(s.getSessionId() != null && s.getSessionId().length() > 10);
    assertTrue(s.getExpiresAt().isAfter(Instant.now()));
    assertEquals("admin", s.getUsername());
  }

  @Test
  @DisplayName("findValid_未过期session命中返")
  void findValid_unexpired_returnsSession() {
    WebSessionService svc = service();
    WebSession created = svc.create("admin");

    assertTrue(svc.findValid(created.getSessionId()).isPresent());
  }

  @Test
  @DisplayName("findValid_不存在的sessionId返empty")
  void findValid_missing_returnsEmpty() {
    assertTrue(service().findValid("nonexistent-uuid").isEmpty());
  }

  @Test
  @DisplayName("findValid_null或blank返empty")
  void findValid_nullOrBlank_returnsEmpty() {
    WebSessionService svc = service();
    assertTrue(svc.findValid(null).isEmpty());
    assertTrue(svc.findValid("").isEmpty());
    assertTrue(svc.findValid("   ").isEmpty());
  }

  @Test
  @DisplayName("findValid_过期session返empty且顺手删除行（惰性清）")
  void findValid_expired_returnsEmptyAndDeletes() {
    // 用 0 ttl 建 service，让所有 session 立即过期
    WebSessionService svc = new WebSessionService(repository, Duration.ZERO);
    WebSession created = svc.create("admin");

    // 过期 → findValid 返 empty
    assertTrue(svc.findValid(created.getSessionId()).isEmpty());
    // 惰性清：行已删
    assertFalse(repository.findBySessionId(created.getSessionId()).isPresent());
  }

  @Test
  @DisplayName("isExpired_过期判定")
  void isExpired_correctJudgment() throws Exception {
    WebSessionService svc = service();
    WebSession s = svc.create("admin");
    assertFalse(svc.isExpired(s));

    // 手动把过期时间设过去
    s.setExpiresAt(Instant.now().minusSeconds(60));
    assertTrue(svc.isExpired(s));
  }

  @Test
  @DisplayName("delete_删行")
  void delete_removesRow() {
    WebSessionService svc = service();
    WebSession created = svc.create("admin");
    assertTrue(repository.findBySessionId(created.getSessionId()).isPresent());

    svc.delete(created.getSessionId());
    assertFalse(repository.findBySessionId(created.getSessionId()).isPresent());
  }

  @Test
  @DisplayName("delete_null或不存在的sessionId幂等不崩")
  void delete_missingIdOrNull_isIdempotent() {
    WebSessionService svc = service();
    svc.delete(null);
    svc.delete("");
    svc.delete("nonexistent");
    // 不抛异常即通过
    assertTrue(true);
  }

  @Test
  @DisplayName("多次create生成不同sessionId")
  void create_multipleDistinctSessionIds() {
    WebSessionService svc = service();
    WebSession a = svc.create("admin");
    WebSession b = svc.create("admin");
    assertFalse(a.getSessionId().equals(b.getSessionId()));
  }
}
