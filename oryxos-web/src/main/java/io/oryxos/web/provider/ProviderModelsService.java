package io.oryxos.web.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.web.error.ProviderUnavailableException;
import io.oryxos.web.error.ResourceNotFoundException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 按 provider name 服务端代理 OpenAI 兼容的 {@code /models} 端点，返回该 provider 下的模型 id 列表。
 *
 * <p>必须服务端发起——不能让浏览器直连（会暴露 api-key 且踩 CORS）；api-key / base-url 取自 {@link ProviderRegistry} （与运行时建
 * ChatModel 用的是同一套参数，宪法 III「显式 name→参数映射」）。mock provider 无真实端点，返回占位 ["mock"]。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "registry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图（与运行时建 ChatModel 共用同一注册表）。")
@Service
public class ProviderModelsService {

  private static final String MOCK = "mock";
  private static final String SLASH = "/";
  private static final String PATH_V1 = "/v1";
  private static final String PATH_MODELS = "/models";
  private static final String VERSIONED_PATH_PATTERN = ".*/v\\d+$";

  /** 连接超时（秒）的系统属性名：默认 10，{@code -Doryxos.provider.models.connect-timeout-seconds=N} 覆盖。 */
  static final String CONNECT_TIMEOUT_PROP = "oryxos.provider.models.connect-timeout-seconds";

  /** 读取超时（秒）的系统属性名：默认 30，{@code -Doryxos.provider.models.read-timeout-seconds=N} 覆盖。 */
  static final String READ_TIMEOUT_PROP = "oryxos.provider.models.read-timeout-seconds";

  private static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
  private static final long DEFAULT_READ_TIMEOUT_SECONDS = 30;

  private final ProviderRegistry registry;
  private final RestClient restClient;

  public ProviderModelsService(ProviderRegistry registry, RestClient.Builder restClientBuilder) {
    this.registry = registry;
    // 不用默认 RestClient（无超时）：provider 挂死会永久占住管理台 /models 与 /test 的工作线程。
    // clone 后再挂 requestFactory，避免污染共享 Builder bean。
    this.restClient = restClientBuilder.clone().requestFactory(timeoutFactory()).build();
  }

  /** 列出某 provider 下的模型 id（按字母排序）。provider 不存在→404；端点不可达/缺 base-url→503。 */
  public List<String> listModels(String providerName) {
    ProviderDef def =
        registry
            .find(providerName)
            .orElseThrow(() -> new ResourceNotFoundException("provider 不存在: " + providerName));
    if (MOCK.equals(def.name())) {
      return List.of("mock");
    }
    String baseUrl = def.baseUrl();
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new ProviderUnavailableException("provider " + providerName + " 未配置 base-url，无法列举模型");
    }
    String apiKey = def.apiKey() == null ? "" : def.apiKey();
    try {
      ModelsResponse resp =
          restClient
              .get()
              .uri(modelsUrl(baseUrl))
              .header("Authorization", "Bearer " + apiKey)
              .retrieve()
              .body(ModelsResponse.class);
      if (resp == null || resp.data() == null) {
        return List.of();
      }
      return resp.data().stream().map(ModelEntry::id).filter(Objects::nonNull).sorted().toList();
    } catch (RuntimeException e) {
      throw new ProviderUnavailableException(
          "无法从 provider " + providerName + " 获取模型列表: " + e.getMessage(), e);
    }
  }

  /**
   * 带连接/读取超时的请求工厂：默认 RestClient 无超时，端点挂死会拖垮 Tomcat 工作线程。构建时读属性，不在类加载期固化。
   *
   * <p>模式同 {@code ProviderChatModelFactory.timeoutFactory()} / {@code
   * OryxOsRuntime.toolHttpRequestFactory()}；models 列表用较短 read timeout（默认 30s）。
   */
  static JdkClientHttpRequestFactory timeoutFactory() {
    Duration connectTimeout =
        Duration.ofSeconds(Long.getLong(CONNECT_TIMEOUT_PROP, DEFAULT_CONNECT_TIMEOUT_SECONDS));
    Duration readTimeout =
        Duration.ofSeconds(Long.getLong(READ_TIMEOUT_PROP, DEFAULT_READ_TIMEOUT_SECONDS));
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                // 禁自动重定向：与 Skill import / HttpTools 同款——防恶意 baseUrl 302→元数据/内网（SSRF）
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    factory.setReadTimeout(readTimeout);
    return factory;
  }

  /**
   * 拼 OpenAI 兼容标准的 {@code /v1/models} 地址：先剥离 baseUrl 末尾的 {@code /} 与 {@code /v1}（用户填带或不带 /v1 都正确），
   * 再统一追加 {@code /v1/models}。与 {@code OpenAiApi} 内部追加 {@code /v1/chat/completions} 的预期对齐： Provider
   * baseUrl 约定不含 {@code /v1}，两个消费者各自补完整 API 路径。 例外：剥离后仍以版本段结尾（如 GLM 的 {@code /api/paas/v4}） 说明版本在
   * baseUrl 里，只补 {@code /models}——与 {@code ProviderChatModelFactory} 的判断规则保持一致。
   */
  private static String modelsUrl(String baseUrl) {
    String u = baseUrl.strip();
    while (u.endsWith(SLASH) || u.endsWith(PATH_V1)) {
      if (u.endsWith(SLASH)) {
        u = u.substring(0, u.length() - 1);
      } else {
        u = u.substring(0, u.length() - PATH_V1.length());
      }
    }
    if (u.matches(VERSIONED_PATH_PATTERN)) {
      return u + PATH_MODELS;
    }
    return u + PATH_V1 + PATH_MODELS;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ModelsResponse(@JsonProperty("data") List<ModelEntry> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ModelEntry(@JsonProperty("id") String id) {}
}
