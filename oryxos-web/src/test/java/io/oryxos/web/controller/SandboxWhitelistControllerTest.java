package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.sandbox.SandboxWhitelist;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.SandboxWhitelistController.WhitelistChange;
import io.oryxos.web.controller.SandboxWhitelistController.WhitelistEntryRequest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 管理端点单测：只依赖 core 的 {@link SandboxWhitelist} 契约（web 不认 tool 实现），用内存替身验证三个端点的 委派、回显与非法输入拒绝。路由与 400
 * 翻译由 Spring + GlobalExceptionHandler 负责，这里断言控制器逻辑。
 */
class SandboxWhitelistControllerTest {

  /** 内存替身：实现 core 契约，够测控制器委派/回显，不牵扯 WhitelistSandbox。 */
  private static final class InMemoryWhitelist implements SandboxWhitelist {
    private final Map<Category, List<String>> store = new EnumMap<>(Category.class);

    InMemoryWhitelist() {
      for (Category category : Category.values()) {
        store.put(category, new ArrayList<>());
      }
    }

    @Override
    public List<String> list(Category category) {
      return List.copyOf(store.get(category));
    }

    @Override
    public boolean add(Category category, String value) {
      List<String> entries = store.get(category);
      if (entries.contains(value)) {
        return false;
      }
      entries.add(value);
      return true;
    }

    @Override
    public boolean remove(Category category, String value) {
      return store.get(category).remove(value);
    }
  }

  private final SandboxWhitelist whitelist = new InMemoryWhitelist();
  private final SandboxWhitelistController controller = new SandboxWhitelistController(whitelist);

  @Test
  @DisplayName("查询返回三类白名单")
  void listReturnsAllThreeCategories() {
    whitelist.add(SandboxWhitelist.Category.HTTP, "*.example.com");

    ApiResponse<Map<String, List<String>>> response = controller.list();

    Map<String, List<String>> data = response.getData();
    assertEquals(3, data.size());
    assertTrue(data.containsKey("file"));
    assertTrue(data.containsKey("shell"));
    assertEquals(List.of("*.example.com"), data.get("http"));
  }

  @Test
  @DisplayName("增加委派到白名单并回显最新全量")
  void addDelegatesAndEchoesEntries() {
    ApiResponse<WhitelistChange> response =
        controller.add("http", new WhitelistEntryRequest("*.example.com"));

    WhitelistChange change = response.getData();
    assertTrue(change.changed());
    assertEquals("http", change.category());
    assertEquals(List.of("*.example.com"), change.entries());
    assertEquals(List.of("*.example.com"), whitelist.list(SandboxWhitelist.Category.HTTP));
  }

  @Test
  @DisplayName("重复增加幂等_changed 为 false")
  void addIsIdempotent() {
    controller.add("shell", new WhitelistEntryRequest("ls"));

    ApiResponse<WhitelistChange> second = controller.add("shell", new WhitelistEntryRequest("ls"));

    assertFalse(second.getData().changed());
    assertEquals(List.of("ls"), whitelist.list(SandboxWhitelist.Category.SHELL));
  }

  @Test
  @DisplayName("删除委派并回显_命中返回 changed true")
  void removeDelegates() {
    whitelist.add(SandboxWhitelist.Category.SHELL, "ls");

    ApiResponse<WhitelistChange> response = controller.remove("shell", "ls");

    assertTrue(response.getData().changed());
    assertTrue(whitelist.list(SandboxWhitelist.Category.SHELL).isEmpty());
  }

  @Test
  @DisplayName("删除不存在条目_changed false 不报错")
  void removeNonexistentReturnsFalse() {
    ApiResponse<WhitelistChange> response = controller.remove("http", "*.absent.com");

    assertFalse(response.getData().changed());
  }

  @Test
  @DisplayName("未知类别_拒绝并点名可选值")
  void unknownCategoryRejected() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> controller.add("dns", new WhitelistEntryRequest("x")));

    assertTrue(ex.getMessage().contains("dns"));
  }

  @Test
  @DisplayName("增加空值_拒绝点名 value")
  void addBlankValueRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> controller.add("http", new WhitelistEntryRequest("")));
    assertThrows(IllegalArgumentException.class, () -> controller.add("http", null));
  }
}
