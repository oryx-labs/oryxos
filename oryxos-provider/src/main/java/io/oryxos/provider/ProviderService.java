package io.oryxos.provider;

import io.oryxos.core.profile.Profile;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Provider 前台：按 Profile 显式路由到对应 ChatModel，完成一次调用并落审计。
 *
 * <p>宪法 II/III：显式 name→ChatModel 映射、调用方式 {@code chatModel.call(new Prompt(...))}、
 * proxyToolCalls=true 关闭框架自动工具执行——工具 schema 只翻译、tool call 原样透传。
 */
public class ProviderService {

  private final Map<String, ChatModel> providerMap;
  private final ToolSchemaAdapter adapter;
  private final LlmCallAuditor audit;

  public ProviderService(
      Map<String, ChatModel> providerMap, ToolSchemaAdapter adapter, LlmCallAuditor audit) {
    this.providerMap = Map.copyOf(providerMap);
    this.adapter = adapter;
    this.audit = audit;
  }

  public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
    String providerName = profile.provider().name();
    ChatModel model = providerMap.get(providerName);
    if (model == null) {
      throw new ProviderNotFoundException(providerName);
    }
    Prompt prompt = buildPrompt(profile, request);
    long startedAt = System.currentTimeMillis();
    try {
      ChatResponse response = model.call(prompt);
      ProviderResponse result = toProviderResponse(response);
      audit.record(
          sessionId,
          providerName,
          profile.provider().model(),
          result.usage(),
          true,
          null,
          System.currentTimeMillis() - startedAt);
      return result;
    } catch (RuntimeException e) {
      // 失败也留痕（宪法 V）：先落审计再上抛——只记成功不记失败，一次真实事故就没有痕迹
      audit.record(
          sessionId,
          providerName,
          profile.provider().model(),
          null,
          false,
          e.getMessage(),
          System.currentTimeMillis() - startedAt);
      throw e;
    }
  }

  private Prompt buildPrompt(Profile profile, ProviderRequest request) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .model(profile.provider().model())
            .proxyToolCalls(Boolean.TRUE); // 关闭自动执行：执行权只在 ToolExecutor（17 节）
    if (profile.provider().temperature() != null) {
      options.temperature(profile.provider().temperature());
    }
    List<FunctionCallback> callbacks = adapter.toSpringAiTools(request.availableTools());
    if (!callbacks.isEmpty()) {
      options.toolCallbacks(callbacks);
    }
    return new Prompt(request.content(), options.build());
  }

  private static ProviderResponse toProviderResponse(ChatResponse response) {
    Generation generation = response.getResult();
    String text = null;
    List<ToolCallRequest> toolCalls = List.of();
    if (generation != null) {
      AssistantMessage output = generation.getOutput();
      text = output.getText();
      toolCalls =
          output.getToolCalls().stream()
              .map(call -> new ToolCallRequest(call.name(), call.arguments()))
              .toList();
    }
    return new ProviderResponse(text, toolCalls, extractUsage(response));
  }

  private static Usage extractUsage(ChatResponse response) {
    if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
      return null;
    }
    org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
    return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }
}
