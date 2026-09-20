package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Spring AI 2.0 / openai-java：baseUrl 需含 {@code /v1}（SDK 再追加 {@code /chat/completions}）。 固化 {@link
 * ProviderChatModelFactory#normalizeBaseUrl} 对历史配置（常不含 /v1）与版本化端点的处理。
 */
class UriBuilderPathReplacementTest {

  @Test
  @DisplayName("普通端点补 /v1，避免缺版本段")
  void plainBaseUrl_getsV1() {
    assertEquals(
        "https://opencode.ai/zen/go/v1",
        ProviderChatModelFactory.normalizeBaseUrl("https://opencode.ai/zen/go"));
    assertEquals(
        "https://api.deepseek.com/v1",
        ProviderChatModelFactory.normalizeBaseUrl("https://api.deepseek.com"));
  }

  @Test
  @DisplayName("已含 /v1 不重复追加")
  void baseUrlAlreadyWithV1_unchanged() {
    assertEquals(
        "https://opencode.ai/zen/go/v1",
        ProviderChatModelFactory.normalizeBaseUrl("https://opencode.ai/zen/go/v1/"));
  }

  @Test
  @DisplayName("末尾版本化端点（如 GLM /v4）不补 /v1")
  void trailingVersionEndpoint_notSuffixedWithV1() {
    assertEquals(
        "https://open.bigmodel.cn/api/paas/v4",
        ProviderChatModelFactory.normalizeBaseUrl("https://open.bigmodel.cn/api/paas/v4/"));
  }
}
