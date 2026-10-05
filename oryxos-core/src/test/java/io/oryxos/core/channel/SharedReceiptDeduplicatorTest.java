package io.oryxos.core.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.cluster.CoordinationStore;
import org.junit.jupiter.api.Test;

/** 两级去重语义（026 T012）：本地命中零 DB 访问；跨副本重复由回执硬闸拦截。 */
class SharedReceiptDeduplicatorTest {

  @Test
  void firstSeenLocally_thenReceiptDecides() {
    CoordinationStore store = mock(CoordinationStore.class);
    when(store.markReceipt("feishu:m1")).thenReturn(true);
    SharedReceiptDeduplicator dedup = new SharedReceiptDeduplicator(store);

    assertThat(dedup.markIfFirst("feishu:m1")).isTrue(); // 本地首见 + 回执首见
    assertThat(dedup.markIfFirst("feishu:m1")).isFalse(); // 本地命中：零 DB 访问
    verify(store, times(1)).markReceipt("feishu:m1"); // 只查过一次库
  }

  @Test
  void crossReplicaDuplicate_rejectedByReceipt() {
    CoordinationStore store = mock(CoordinationStore.class);
    when(store.markReceipt("feishu:m2")).thenReturn(false); // 另一副本已处理（重推跨副本）
    SharedReceiptDeduplicator dedup = new SharedReceiptDeduplicator(store);

    assertThat(dedup.markIfFirst("feishu:m2")).isFalse();
    assertThat(dedup.markIfFirst("feishu:m2")).isFalse(); // 撞冲突后也进了本地缓存
    verify(store, times(1)).markReceipt("feishu:m2");
  }

  @Test
  void releaseClearsBothLevels() {
    CoordinationStore store = mock(CoordinationStore.class);
    when(store.markReceipt("feishu:m3")).thenReturn(true);
    SharedReceiptDeduplicator dedup = new SharedReceiptDeduplicator(store);

    assertThat(dedup.markIfFirst("feishu:m3")).isTrue();
    dedup.release("feishu:m3");
    verify(store, times(1)).unmarkReceipt("feishu:m3"); // 共享回执要撤
    assertThat(dedup.markIfFirst("feishu:m3")).isTrue(); // 本地缓存也要撤，否则这里是 false
  }
}
