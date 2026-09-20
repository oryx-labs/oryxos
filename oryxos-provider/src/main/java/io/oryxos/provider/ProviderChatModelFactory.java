package io.oryxos.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClientAsync;
import com.openai.core.Timeout;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

/**
 * 按全局配置逐条手工构造 ChatModel，产出显式 name→ChatModel 映射表（宪法 III）。
 *
 * <p>不使用任何 starter 自动装配（宪法 II 禁 eager 装配）；deepseek/kimi 均为 OpenAI 兼容端点，经 {@code spring-ai-openai} +
 * 官方 openai-java OkHttp 客户端接入。
 *
 * <p>Spring AI 2.0 移除了手写 {@code OpenAiApi}/{@code RestClient} 路径，改为 {@link OpenAIClient}；重试收敛到
 * {@code maxRetries(0)}（单次尝试），「重试」语义整层上收到 fallback 切换循环（023 R8）。
 */
public class ProviderChatModelFactory {

  /** 内置 mock provider 的保留名：配置里 {@code - name: mock} 即挂一个假模型，用于无 key 全链路自测。 */
  static final String MOCK = "mock";

  private static final String SLASH = "/";
  private static final String PATH_V1 = "/v1";

  /** 连接超时（秒）的系统属性名：默认 10，{@code -Doryxos.llm.connect-timeout-seconds=N} 覆盖。 */
  static final String CONNECT_TIMEOUT_PROP = "oryxos.llm.connect-timeout-seconds";

  /** 读取超时（秒）的系统属性名：默认 120（推理模型长回答留足余量），{@code -Doryxos.llm.read-timeout-seconds=N} 覆盖。 */
  static final String READ_TIMEOUT_PROP = "oryxos.llm.read-timeout-seconds";

  private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final long DEFAULT_READ_TIMEOUT_SECONDS = 120;

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
    String base = normalizeBaseUrl(baseUrl);
    return OpenAiChatModel.builder()
        .openAiClient(syncClient(base, apiKey))
        .openAiClientAsync(asyncClient(base, apiKey))
        .build();
  }

  /** 同步 OpenAI 兼容客户端：超时 + 禁重定向 + 零 SDK 重试。 */
  static OpenAIClient syncClient(String normalizedBaseUrl, String apiKey) {
    return clientBuilder(normalizedBaseUrl, apiKey).build();
  }

  /** 异步客户端与同步共用同一套凭证/超时，避免 Spring AI 回退读 {@code OPENAI_API_KEY} 环境变量。 */
  static OpenAIClientAsync asyncClient(String normalizedBaseUrl, String apiKey) {
    return asyncClientBuilder(normalizedBaseUrl, apiKey).build();
  }

  /** 包可见：单测断言 followRedirects / timeout 装配。 */
  static OpenAIOkHttpClient.Builder clientBuilder(String normalizedBaseUrl, String apiKey) {
    return OpenAIOkHttpClient.builder()
        .baseUrl(normalizedBaseUrl)
        .apiKey(apiKey)
        .timeout(requestTimeout())
        .maxRetries(0)
        .followRedirects(false);
  }

  static OpenAIOkHttpClientAsync.Builder asyncClientBuilder(
      String normalizedBaseUrl, String apiKey) {
    return OpenAIOkHttpClientAsync.builder()
        .baseUrl(normalizedBaseUrl)
        .apiKey(apiKey)
        .timeout(requestTimeout())
        .maxRetries(0)
        .followRedirects(false);
  }

  /** 连接/读取超时：默认 RestClient 时代无超时会把同步 ReAct 循环连带会话永久卡住。 */
  static Timeout requestTimeout() {
    Duration connect =
        Duration.ofSeconds(Long.getLong(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TIMEOUT_SECONDS));
    Duration read =
        Duration.ofSeconds(Long.getLong(READ_TIMEOUT_PROP, DEFAULT_READ_TIMEOUT_SECONDS));
    return Timeout.builder().connect(connect).read(read).write(read).request(read).build();
  }

  /**
   * openai-java 以 {@code .../v1} 为 base，再追加 {@code /chat/completions}。用户配置历史上常不含 /v1（旧 OpenAiApi
   * 会自动补），此处补齐；GLM 等版本已在 path 末尾的端点不补。
   */
  static String normalizeBaseUrl(String baseUrl) {
    String u = stripTrailingSlash(baseUrl == null ? "" : baseUrl.strip());
    if (u.isEmpty()) {
      return u;
    }
    if (TRAILING_VERSION.matcher(u).matches()) {
      return u;
    }
    if (u.endsWith(PATH_V1)) {
      return u;
    }
    return u + PATH_V1;
  }

  private static String stripTrailingSlash(String baseUrl) {
    String u = baseUrl;
    while (u.endsWith(SLASH)) {
      u = u.substring(0, u.length() - SLASH.length());
    }
    return u;
  }
}
