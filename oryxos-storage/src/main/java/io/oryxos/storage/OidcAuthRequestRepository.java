package io.oryxos.storage;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** oidc_auth_requests 的访问通道；consume 是单次消费的 CAS 原子操作（026 纪律）。 */
public interface OidcAuthRequestRepository extends JpaRepository<OidcAuthRequestEntity, String> {

  /**
   * CAS 消费：仅当未消费且未过期时置 consumed_at。返回 0 = 重放/过期/未知 state，一律拒绝。
   *
   * <p>多副本并发回调同一 state 时数据库保证恰好一个成功。
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "UPDATE OidcAuthRequestEntity r SET r.consumedAt = :now"
          + " WHERE r.state = :state AND r.consumedAt IS NULL AND r.expiresAt > :now")
  int consume(@Param("state") String state, @Param("now") Instant now);

  /** 惰性清理：删过期行（无后台定时线程，随 create/consume 顺手执行）。 */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM OidcAuthRequestEntity r WHERE r.expiresAt < :now")
  int purgeExpired(@Param("now") Instant now);
}
