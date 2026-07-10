package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.provider.ProvidersProperties.ProviderConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 全局层配置校验：缺什么点名什么，不静默（T012）。 */
class ProvidersPropertiesTest {

  @Test
  void 合法配置_校验通过() {
    ProvidersProperties props =
        new ProvidersProperties(
            List.of(
                new ProviderConfig("deepseek", "resolved-key", "https://api.deepseek.com"),
                new ProviderConfig("kimi", "resolved-key", "https://api.moonshot.cn/v1")));

    assertDoesNotThrow(props::validate);
  }

  @Test
  void provider名重复_报错点名() {
    ProvidersProperties props =
        new ProvidersProperties(
            List.of(
                new ProviderConfig("deepseek", "k", "https://a"),
                new ProviderConfig("deepseek", "k", "https://b")));

    IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
    assertTrue(ex.getMessage().contains("deepseek"));
  }

  @Test
  void 环境变量未解析_报错点名provider() {
    ProvidersProperties props =
        new ProvidersProperties(
            List.of(new ProviderConfig("kimi", "${KIMI_API_KEY}", "https://api.moonshot.cn/v1")));

    IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
    assertTrue(ex.getMessage().contains("kimi")); // 点名哪家的 key 没配好
  }

  @Test
  void 缺baseUrl_报错点名() {
    ProvidersProperties props =
        new ProvidersProperties(List.of(new ProviderConfig("kimi", "key", " ")));

    IllegalStateException ex = assertThrows(IllegalStateException.class, props::validate);
    assertTrue(ex.getMessage().contains("kimi"));
  }
}
