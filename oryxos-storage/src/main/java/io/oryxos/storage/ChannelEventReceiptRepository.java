package io.oryxos.storage;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** 回执判重（026）：插入捕唯一约束冲突即重复；超龄行由心跳循环批删（原进程内 TTL 口径）。 */
public interface ChannelEventReceiptRepository extends JpaRepository<ChannelEventReceipt, String> {

  @Transactional(rollbackFor = Exception.class)
  @Modifying
  @Query(
      value =
          "INSERT INTO channel_event_receipts (receipt_key, first_seen_at)"
              + " VALUES (:receiptKey, :firstSeenAt)",
      nativeQuery = true)
  void insertReceipt(String receiptKey, Instant firstSeenAt);

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM ChannelEventReceipt r WHERE r.firstSeenAt < :before")
  int deleteOlderThan(Instant before);

  @Transactional(rollbackFor = Exception.class)
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM ChannelEventReceipt r WHERE r.receiptKey = :receiptKey")
  int deleteReceipt(String receiptKey);
}
