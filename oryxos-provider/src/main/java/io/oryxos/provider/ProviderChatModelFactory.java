package io.oryxos.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

/**
 * 按全局配置逐条手工构造 ChatModel，产出显式 name→ChatModel 映射表（宪法 III）。
 *
 * <p>不使用任何 starter 自动装配（宪法 II 禁 eager 装配）；deepseek/kimi 均为 OpenAI 兼容端点， 经 {@code spring-ai-openai}
 * 的 OpenAiApi(baseUrl, apiKey) 接入（research D1，T003 已核实签名）。
 */
public class ProviderChatModelFactory {

  /** 内置 mock provider 的保留名：配置里 {@code - name: mock} 即挂一个假模型，用于无 key 全链路自测。 */
  static final String MOCK = "mock";

  private static final String SLASH = "/";
  private static final String PATH_V1 = "/v1";

  /** 末尾版本段（如 GLM 的 /api/paas/v4）：此类端点版本在 baseUrl 里，不能再补 /v1。 */
  private static final java.util.regex.Pattern TRAILING_VERSION =
      java.util.regex.Pattern.compile(".*/v\\d+$");

  public Map<String, ChatModel> build(ProvidersProperties properties) {
    properties.validate();
    Map<String, ChatModel> providerMap = new LinkedHashMap<>();
    for (ProvidersProperties.ProviderConfig config : properties.providers()) {
      providerMap.put(config.name(), buildOne(config.name(), config.apiKey(), config.baseUrl()));
    }
    return providerMap;
  }

  /** 按单个 provider 的参数手工构造 ChatModel（31 节动态 provider：按名从注册表取参数后即时建）。mock 名走内置假模型。 */
  public ChatModel buildOne(String name, String apiKey, String baseUrl) {
    if (MOCK.equals(name)) {
      return new MockChatModel(); // 不连真实端点，无需 key/url
    }
    // baseUrl 约定不含 /v1：OpenAiApi 内部会追加 /v1/chat/completions；用户填带 /v1 则先剥离，避免双 /v1（fix-issue-47）
    String base = stripTrailingV1(baseUrl);
    OpenAiApi.Builder api = OpenAiApi.builder().baseUrl(base).apiKey(apiKey);
    if (TRAILING_VERSION.matcher(base).matches()) {
      // 端点版本在 baseUrl 里（如 GLM 的 /api/paas/v4），改补无版本的 /chat/completions
      api.completionsPath("/chat/completions");
    }
    return OpenAiChatModel.builder().openAiApi(api.build()).build();
  }

  /**
   * 剥离 baseUrl 末尾的 {@code /} 与 {@code /v1}，与 {@link
   * io.oryxos.web.provider.ProviderModelsService#modelsUrl} 对齐。
   */
  private static String stripTrailingV1(String baseUrl) {
    String u = baseUrl == null ? "" : baseUrl.strip();
    while (u.endsWith(SLASH) || u.endsWith(PATH_V1)) {
      if (u.endsWith(SLASH)) {
        u = u.substring(0, u.length() - SLASH.length());
      } else {
        u = u.substring(0, u.length() - PATH_V1.length());
      }
    }
    return u;
  }
}
