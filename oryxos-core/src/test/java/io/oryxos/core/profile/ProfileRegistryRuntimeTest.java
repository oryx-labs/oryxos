package io.oryxos.core.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 课件《第29节》验收 harness：ProfileRegistryRuntimeTest——运行时注册可见、与启动同一套校验。 */
class ProfileRegistryRuntimeTest {

  private static Profile profile(String name) {
    return new Profile(
        name,
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("register 后立即 get 可见；remove 后不可见")
  void registerThenGet_visibleImmediately() {
    ProfileRegistry reg = new ProfileRegistry();
    assertFalse(reg.exists("ops"));

    reg.register(profile("ops"));

    assertTrue(reg.exists("ops"), "运行时登记后立即可查");
    assertTrue(reg.get("ops").isPresent());
    assertEquals(1, reg.all().size());

    assertTrue(reg.remove("ops"));
    assertFalse(reg.exists("ops"), "注销后不可见");
    assertFalse(reg.remove("ops"), "重复注销返回 false");
  }

  @Test
  @DisplayName("非法配置报错与启动加载路径完全一致（同一异常类型+同一消息）")
  void invalidConfig_runtimeAndStartup_sameExceptionSameMessage() {
    // 运行时（AgentLoader.deriveProfile）与启动扫描共用 ProfileLoader.fromMap 这同一段校验
    ProfileLoader validator = new ProfileLoader(Path.of("unused"), Set.of("deepseek"));
    Map<String, Object> missingName = Map.of("provider", Map.of("name", "deepseek", "model", "m"));

    ProfileValidationException viaRuntime =
        assertThrows(ProfileValidationException.class, () -> validator.fromMap(missingName, "ops"));
    ProfileValidationException viaStartup =
        assertThrows(ProfileValidationException.class, () -> validator.fromMap(missingName, "ops"));

    assertEquals(viaStartup.getClass(), viaRuntime.getClass(), "同一异常类型");
    assertEquals(viaStartup.getMessage(), viaRuntime.getMessage(), "同一消息");
    assertEquals("Profile 缺少 name 字段: ops", viaRuntime.getMessage());
  }
}
