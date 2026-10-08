package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 018 验收 harness：ApiKeyServiceTest——凭证生成/校验/吊销口径钉死。守：明文格式（oryx_ 前缀 + 42 位 base62）、 库中只有 64 位 hex
 * 哈希无明文、verify 对/错/不存在/已吊销四路、revoke 幂等、重名拒绝、last_used 60s 节流。
 */
class ApiKeyServiceTest {

  private ApiKeyRepository repository;
  private ApiKeyService service;

  @BeforeEach
  void setUp() {
    repository = mock(ApiKeyRepository.class);
    when(repository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new ApiKeyService(repository);
  }

  @Test
  @DisplayName("create_明文为oryx_前缀+42位base62_共47字符")
  void create_plaintextFormat() {
    when(repository.existsByName("ci-bot")).thenReturn(false);

    ApiKeyService.CreatedKey created = service.create("ci-bot");

    assertThat(created.plaintext()).startsWith("oryx_").hasSize("oryx_".length() + 42);
    assertThat(created.plaintext().substring(5)).matches("[A-Za-z0-9]{42}");
  }

  @Test
  @DisplayName("create_落库只有64位hex哈希与前缀_无明文")
  void create_storesHashNotPlaintext() {
    when(repository.existsByName("ci-bot")).thenReturn(false);

    ApiKeyService.CreatedKey created = service.create("ci-bot");
    ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
    verify(repository).save(captor.capture());
    ApiKey saved = captor.getValue();

    assertThat(saved.getKeyHash()).matches("[0-9a-f]{64}").isNotEqualTo(created.plaintext());
    assertThat(saved.getKeyPrefix())
        .isEqualTo(created.plaintext().substring(0, "oryx_".length() + 8));
    assertThat(saved.getName()).isEqualTo("ci-bot");
    assertThat(saved.isActive()).isTrue();
  }

  @Test
  @DisplayName("create_重名拒绝_不覆盖")
  void create_duplicateNameRejected() {
    when(repository.existsByName("ci-bot")).thenReturn(true);

    assertThatThrownBy(() -> service.create("ci-bot"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("already exists");
    verify(repository, times(0)).save(any());
  }

  @Test
  @DisplayName("create_名称为空/超长/含空白_拒绝")
  void create_invalidNameRejected() {
    assertThatThrownBy(() -> service.create("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("a".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("ci bot")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("verify_正确明文_true且更新last_used")
  void verify_correctKey() {
    when(repository.existsByName("ci-bot")).thenReturn(false);
    ApiKeyService.CreatedKey created = service.create("ci-bot");
    stubFindByHash(created.key());

    assertThat(service.verify(created.plaintext())).isTrue();
    assertThat(created.key().getLastUsedAt()).isNotNull();
  }

  @Test
  @DisplayName("verify_错误/不存在/格式错/null_均false")
  void verify_wrongKeyVariants() {
    when(repository.findByKeyHash(anyString())).thenReturn(Optional.empty());

    assertThat(service.verify("oryx_" + "A".repeat(42))).isFalse();
    assertThat(service.verify("not-a-key")).isFalse();
    assertThat(service.verify("")).isFalse();
    assertThat(service.verify(null)).isFalse();
  }

  @Test
  @DisplayName("verify_已吊销Key_false_与不存在同结果")
  void verify_revokedKey_false() {
    when(repository.existsByName("ci-bot")).thenReturn(false);
    ApiKeyService.CreatedKey created = service.create("ci-bot");
    created.key().setRevokedAt(Instant.now());
    stubFindByHash(created.key());

    assertThat(service.verify(created.plaintext())).isFalse();
  }

  @Test
  @DisplayName("revoke_有效Key_true并写revoked_at_再吊销幂等false")
  void revoke_thenIdempotent() {
    ApiKey key = namedKey("ci-bot");
    when(repository.findByName("ci-bot")).thenReturn(Optional.of(key));

    assertThat(service.revoke("ci-bot")).isTrue();
    assertThat(key.getRevokedAt()).isNotNull();
    assertThat(service.revoke("ci-bot")).isFalse();
  }

  @Test
  @DisplayName("revoke_不存在名称_抛清晰异常")
  void revoke_unknownName() {
    when(repository.findByName("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.revoke("ghost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not found");
  }

  @Test
  @DisplayName("last_used_60秒内重复verify只落库一次（节流）")
  void verify_lastUsedThrottled() {
    when(repository.existsByName("ci-bot")).thenReturn(false);
    ApiKeyService.CreatedKey created = service.create("ci-bot");
    ReflectionTestUtils.setField(created.key(), "id", 7L);
    stubFindByHash(created.key());

    assertThat(service.verify(created.plaintext())).isTrue();
    assertThat(service.verify(created.plaintext())).isTrue();
    assertThat(service.verify(created.plaintext())).isTrue();

    // save 共 2 次：create 一次 + 首次 verify 落 last_used 一次；后两次 verify 被节流
    verify(repository, times(2)).save(any(ApiKey.class));
  }

  @Test
  @DisplayName("last_used_落库失败_verify仍返回true（不阻断）")
  void verify_lastUsedFailureDoesNotBlock() {
    when(repository.existsByName("ci-bot")).thenReturn(false);
    ApiKeyService.CreatedKey created = service.create("ci-bot");
    stubFindByHash(created.key());
    when(repository.save(any(ApiKey.class))).thenThrow(new RuntimeException("db busy"));

    assertThat(service.verify(created.plaintext())).isTrue();
  }

  @Test
  @DisplayName("hasActiveKey_全吊销false_有未吊销true")
  void hasActiveKey() {
    ApiKey revoked = namedKey("a");
    revoked.setRevokedAt(Instant.now());
    when(repository.findAll()).thenReturn(java.util.List.of(revoked));
    assertThat(service.hasActiveKey()).isFalse();

    when(repository.findAll()).thenReturn(java.util.List.of(revoked, namedKey("b")));
    assertThat(service.hasActiveKey()).isTrue();
  }

  @Test
  @DisplayName("list_按名称排序_返回实体不含任何明文")
  void list_sortedNoPlaintext() {
    when(repository.findAll()).thenReturn(java.util.List.of(namedKey("zeta"), namedKey("alpha")));

    var keys = service.list();

    assertThat(keys).extracting(ApiKey::getName).containsExactly("alpha", "zeta");
    verify(repository, atLeastOnce()).findAll();
  }

  /** 只 stub 正向命中；其它哈希走 Mockito 默认的 Optional.empty()。 */
  private void stubFindByHash(ApiKey key) {
    when(repository.findByKeyHash(key.getKeyHash())).thenReturn(Optional.of(key));
  }

  private static ApiKey namedKey(String name) {
    ApiKey key = new ApiKey();
    key.setName(name);
    key.setKeyPrefix("oryx_test1234");
    key.setKeyHash("0".repeat(64));
    return key;
  }

  @Test
  @DisplayName("revoke_与 create 同一个字符串_带首尾空格也要吊销得掉")
  void revoke_acceptsThePaddedNameThatCreatedTheKey() {
    when(repository.existsByName("deploy-key")).thenReturn(false);
    ApiKeyService.CreatedKey created = service.create("  deploy-key  ");
    assertThat(created.key().getName()).isEqualTo("deploy-key"); // 前提：create 已归一

    ApiKey key = namedKey("deploy-key");
    when(repository.findByName("deploy-key")).thenReturn(Optional.of(key));

    assertThat(service.revoke("  deploy-key  ")).isTrue();
  }
}
