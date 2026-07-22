package io.oryxos.tool.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 31 节：store-backed WhitelistSandbox 的加载 / 写穿行为（宪法 VI 白名单持久化）。 */
class WhitelistSandboxPersistenceTest {

  /** 内存假 store：验证写穿与恢复，无需真实 SQLite。 */
  static final class FakeStore implements SandboxWhitelistStore {
    private final List<Entry> entries = new ArrayList<>();

    @Override
    public List<Entry> loadAll() {
      return List.copyOf(entries);
    }

    @Override
    public boolean add(Category category, String value) {
      Entry e = new Entry(category, value);
      if (entries.contains(e)) {
        return false;
      }
      entries.add(e);
      return true;
    }

    @Override
    public boolean remove(Category category, String value) {
      return entries.remove(new Entry(category, value));
    }
  }

  @Test
  @DisplayName("add 写穿落库：三类白名单的新增都进 store")
  void add_writesThroughToStore() {
    FakeStore store = new FakeStore();
    WhitelistSandbox sandbox = new WhitelistSandbox(store);

    sandbox.add(Category.SHELL, "ls");
    sandbox.add(Category.HTTP, "api.deepseek.com");
    sandbox.add(Category.FILE, ".oryxos");

    assertThat(store.loadAll()).hasSize(3);
    // FILE 落库的是规范形（绝对路径），与 list 展示一致
    assertThat(store.loadAll())
        .anyMatch(e -> e.category() == Category.FILE && e.value().endsWith(".oryxos"));
    assertThat(sandbox.list(Category.SHELL)).contains("ls");
  }

  @Test
  @DisplayName("幂等：重复 add 不重复落库")
  void add_isIdempotent() {
    FakeStore store = new FakeStore();
    WhitelistSandbox sandbox = new WhitelistSandbox(store);

    sandbox.add(Category.SHELL, "ls");
    sandbox.add(Category.SHELL, "ls");

    assertThat(store.loadAll()).hasSize(1);
  }

  @Test
  @DisplayName("恢复：新 WhitelistSandbox 从 store 载回全部白名单")
  void constructor_loadsFromStore() {
    FakeStore store = new FakeStore();
    new WhitelistSandbox(store).add(Category.SHELL, "cat"); // 落库
    new WhitelistSandbox(store).add(Category.HTTP, "*.feishu.cn");

    WhitelistSandbox restored = new WhitelistSandbox(store);

    assertThat(restored.list(Category.SHELL)).contains("cat");
    assertThat(restored.list(Category.HTTP)).contains("*.feishu.cn");
  }

  @Test
  @DisplayName("remove 写穿落库：删除后 store 不再持有")
  void remove_writesThroughToStore() {
    FakeStore store = new FakeStore();
    WhitelistSandbox sandbox = new WhitelistSandbox(store);
    sandbox.add(Category.SHELL, "ls");

    boolean removed = sandbox.remove(Category.SHELL, "ls");

    assertThat(removed).isTrue();
    assertThat(store.loadAll()).isEmpty();
    assertThat(sandbox.list(Category.SHELL)).doesNotContain("ls");
  }
}
