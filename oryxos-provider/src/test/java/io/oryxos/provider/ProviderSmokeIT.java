package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.Usage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 集成冒烟：真调一次模型，验证"key 对、依赖对、真的通"。
 *
 * <p>默认被 surefire 排除（CI 跳过）；本地跑法见 quickstart： {@code DEEPSEEK_API_KEY=xxx mvn test
 * -Dgroups=integration}。
 */
@Tag("integration")
class ProviderSmokeIT {

  /** 内存审计：冒烟只断言审计路径触发（完整 llm_calls 落库链路由 LlmCallRepositoryTest 覆盖）。 */
  private static final class RecordingAuditor implements LlmCallAuditor {
    private final List<Boolean> successes = new ArrayList<>();

    @Override
    public void record(
        String sessionId,
        String provider,
        String model,
        Usage usage,
        boolean success,
        String errorMessage,
        long durationMs) {
      successes.add(success);
    }
  }

  @Test
  void 真实调用一次_拿到响应且审计success为true() {
    String apiKey = System.getenv("DEEPSEEK_API_KEY");
    assumeTrue(apiKey != null && !apiKey.isBlank(), "未设置 DEEPSEEK_API_KEY，跳过冒烟");

    RecordingAuditor auditor = new RecordingAuditor();
    io.oryxos.core.provider.ProviderRegistry registry =
        org.mockito.Mockito.mock(io.oryxos.core.provider.ProviderRegistry.class);
    org.mockito.Mockito.when(registry.find("deepseek"))
        .thenReturn(
            java.util.Optional.of(
                new io.oryxos.core.provider.ProviderDef(
                    "deepseek", apiKey, "https://api.deepseek.com", null)));
    ProviderChatModelFactory factory = new ProviderChatModelFactory();
    ProviderService service =
        new SpringAiProviderServiceImpl(
            registry,
            def -> factory.buildOne(def.name(), def.apiKey(), def.baseUrl()),
            new ToolSchemaAdapter(),
            auditor);
    Profile profile =
        new Profile(
            "smoke",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    ProviderResponse response =
        service.chat("smoke-session", profile, ProviderRequest.of("用一句话介绍你自己"));

    assertNotNull(response.text());
    assertEquals(List.of(Boolean.TRUE), auditor.successes);
  }
}
