package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 040 验收 harness：OidcAuthRequestStore——CAS 单次消费口径钉死（真 SQLite + V10 迁移建表）。
 * 守：三元组熵与格式、消费恰好一次（重放/未知/过期一律空）、惰性清理不误删未过期行。
 */
@SqliteJpaTest
class OidcAuthRequestStoreSqliteTest {

  @TempDir static Path dbDir;

  @Autowired private OidcAuthRequestRepository repository;

  private OidcAuthRequestStore store;

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbDir.resolve("test.db"));
  }

  @BeforeEach
  void setUp() {
    store = new OidcAuthRequestStore(repository);
  }

  @Test
  @DisplayName("create_三元组均为43字符URL-safe随机串且互不相同")
  void create_tokenFormat() {
    OidcAuthRequestStore.PendingAuth pending = store.create();
    assertThat(pending.state()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    assertThat(pending.nonce()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    assertThat(pending.pkceVerifier()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
    assertThat(pending.state()).isNotEqualTo(pending.nonce()).isNotEqualTo(pending.pkceVerifier());
  }

  @Test
  @DisplayName("consume_首次成功返原三元组_重放空（CAS 单次消费）")
  void consume_exactlyOnce() {
    OidcAuthRequestStore.PendingAuth pending = store.create();

    Optional<OidcAuthRequestStore.PendingAuth> first = store.consume(pending.state());
    assertThat(first).isPresent();
    assertThat(first.get().nonce()).isEqualTo(pending.nonce());
    assertThat(first.get().pkceVerifier()).isEqualTo(pending.pkceVerifier());

    assertThat(store.consume(pending.state())).isEmpty();
  }

  @Test
  @DisplayName("consume_未知state与非法输入一律空")
  void consume_unknownOrInvalid() {
    assertThat(store.consume("unknown-state")).isEmpty();
    assertThat(store.consume(null)).isEmpty();
    assertThat(store.consume("")).isEmpty();
    assertThat(store.consume("x".repeat(65))).isEmpty();
  }

  @Test
  @DisplayName("consume_过期行拒绝且create惰性清理过期行")
  void consume_expiredRejectedAndPurged() {
    OidcAuthRequestEntity expired = new OidcAuthRequestEntity();
    expired.setState("expired-state");
    expired.setNonce("n");
    expired.setPkceVerifier("v");
    expired.setExpiresAt(Instant.now().minusSeconds(60));
    repository.save(expired);

    assertThat(store.consume("expired-state")).isEmpty();

    OidcAuthRequestStore.PendingAuth alive = store.create();
    assertThat(repository.findById("expired-state")).isEmpty();
    assertThat(repository.findById(alive.state())).isPresent();
  }
}
