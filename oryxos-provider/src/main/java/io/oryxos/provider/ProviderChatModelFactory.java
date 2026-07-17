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

  public Map<String, ChatModel> build(ProvidersProperties properties) {
    properties.validate();
    Map<String, ChatModel> providerMap = new LinkedHashMap<>();
    for (ProvidersProperties.ProviderConfig config : properties.providers()) {
      if (MOCK.equals(config.name())) {
        providerMap.put(config.name(), new MockChatModel()); // 不连真实端点，无需 key/url
        continue;
      }
      OpenAiApi api = new OpenAiApi(config.baseUrl(), config.apiKey());
      providerMap.put(config.name(), new OpenAiChatModel(api));
    }
    return providerMap;
  }
}
