package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 复现并固化 Spring {@link UriComponentsBuilder#path(String)} 的拼接语义，定位 OryxOS Chat 调用 404 的根因。
 *
 * <p>关键事实（实测 Spring 6.1.14 / spring-ai-openai 1.0.0-M6）：
 *
 * <ul>
 *   <li>绝对 path（以 {@code /} 开头）：**追加**到 baseUrl 已有 path 末尾，自动插一个 {@code /} 分隔符。
 *   <li>相对 path（不以 {@code /} 开头）：原样拼接到 baseUrl path 末尾，**不插分隔符**。
 * </ul>
 *
 * <p>由此看 OryxOS 的 bug：Provider baseUrl 填 {@code https://opencode.ai/zen/go/v1}， {@code OpenAiApi}
 * 内部用绝对 path {@code /v1/chat/completions} 追加 → 实际请求 {@code
 * https://opencode.ai/zen/go/v1/v1/chat/completions}（**双 /v1**），上游主站不认此路径， 返回 HTML 404 页面。{@code
 * deepseek} 没踩坑是因为其 baseUrl 无子路径，{code /v1} 只出现一次。
 */
class UriBuilderPathReplacementTest {

  @Test
  @DisplayName("绝对 path /v1/chat/completions → 追加到 baseUrl /zen/go/v1 后 → 双 /v1（bug 根因）")
  void absolutePath_appendedToBaseUrlWithV1_yieldsDoubleV1() {
    String built =
        UriComponentsBuilder.fromHttpUrl("https://opencode.ai/zen/go/v1")
            .path("/v1/chat/completions")
            .build()
            .toUriString();

    // 双 /v1：OpenAiApi 内部 + baseUrl 字段各加了一次 → 上游返回 404
    assertEquals("https://opencode.ai/zen/go/v1/v1/chat/completions", built);
  }

  @Test
  @DisplayName("修复方案：baseUrl 去掉 /v1 + 绝对 path /v1/chat/completions → 单 /v1 正确")
  void absolutePath_onBaseUrlWithoutV1_yieldsSingleV1() {
    String built =
        UriComponentsBuilder.fromHttpUrl("https://opencode.ai/zen/go")
            .path("/v1/chat/completions")
            .build()
            .toUriString();

    assertEquals("https://opencode.ai/zen/go/v1/chat/completions", built);
  }

  @Test
  @DisplayName("deepseek baseUrl 无子路径，绝对 path 追加结果恰好 = 单 /v1（掩盖了 bug）")
  void absolutePath_onRootlessBaseUrl_works() {
    String built =
        UriComponentsBuilder.fromHttpUrl("https://api.deepseek.com")
            .path("/v1/chat/completions")
            .build()
            .toUriString();

    assertEquals("https://api.deepseek.com/v1/chat/completions", built);
  }

  @Test
  @DisplayName("相对 path（无前导 /）不插分隔符 → 直接拼接，会咬字，不可用")
  void relativePath_noSeparatorInserted() {
    String built =
        UriComponentsBuilder.fromHttpUrl("https://opencode.ai/zen/go")
            .path("v1/chat/completions")
            .build()
            .toUriString();

    // got gov1（咬字），证明必须传绝对 path 才能凑出正确 /v1/chat/completions
    assertEquals("https://opencode.ai/zen/gov1/chat/completions", built);
  }

  @Test
  @DisplayName("modelsUrl 等价：baseUrl 含 /v1 + /models → 单次 /v1/models，与 Chat 调用对 /v1 的预期不一致")
  void modelsPath_onBaseUrlWithV1_yieldsSingleV1Models() {
    String built =
        UriComponentsBuilder.fromHttpUrl("https://opencode.ai/zen/go/v1")
            .path("/models")
            .build()
            .toUriString();

    // ProviderModelsService.modelsUrl() 直接拼字符串 → /v1/models 用这里的 baseUrl 没问题；
    // 但同一 baseUrl 传给 OpenAiApi 会双 /v1（见上）—— 两端对 baseUrl 的预期矛盾，是 bug 另一面
    assertEquals("https://opencode.ai/zen/go/v1/models", built);
  }
}
