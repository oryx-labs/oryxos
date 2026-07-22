package io.oryxos.provider;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.provider.Usage;
import io.oryxos.core.session.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Provider 前台（core {@link ProviderService} 契约的 Spring AI 实现）：按 Profile 显式路由到对应
 * ChatModel，完成一次调用并落审计。
 *
 * <p>宪法 II/III：显式 name→ChatModel 映射、调用方式 {@code chatModel.call(new Prompt(...))}、
 * proxyToolCalls=true 关闭框架自动工具执行——工具 schema 只翻译、tool call 原样透传。
 */
public class SpringAiProviderServiceImpl implements ProviderService {

  private final ProviderRegistry registry;
  private final Function<ProviderDef, ChatModel> chatModelBuilder;
  private final ToolSchemaAdapter adapter;
  private final LlmCallAuditor audit;
  // 已建的 ChatModel 缓存：key = name|apiKey|baseUrl；provider 改了 key/url（缓存键变）→ 下次自动重建（31 节动态 provider）
  private final Map<String, ChatModel> cache = new ConcurrentHashMap<>();

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "registry/builder/adapter/audit 均为 Spring 注入的共享单例，构造注入共享同一引用正是意图")
  public SpringAiProviderServiceImpl(
      ProviderRegistry registry,
      Function<ProviderDef, ChatModel> chatModelBuilder,
      ToolSchemaAdapter adapter,
      LlmCallAuditor audit) {
    this.registry = registry;
    this.chatModelBuilder = chatModelBuilder;
    this.adapter = adapter;
    this.audit = audit;
  }

  @Override
  public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
    String providerName = profile.provider().name();
    // 宪法 III：仍是按 name 的显式查找，只是从"启动静态 map"变成"运行时注册表 + 按名动态建/缓存"
    ProviderDef def =
        registry.find(providerName).orElseThrow(() -> new ProviderNotFoundException(providerName));
    ChatModel model = resolveModel(def);
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

  /** 按 name+key+url 缓存构建好的 ChatModel；参数变化即换缓存键、下次重建（provider CRUD 改了配置立即生效）。 */
  private ChatModel resolveModel(ProviderDef def) {
    String cacheKey = def.name() + "|" + def.apiKey() + "|" + def.baseUrl();
    return cache.computeIfAbsent(cacheKey, k -> chatModelBuilder.apply(def));
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
    // 结构化消息透传（31 节修复）：system + 逐条对话消息，保留 assistant tool_calls / tool tool_call_id 配对，
    // 让模型看出工具已调过、继续下一步而不是反复重调。
    List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
    if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
      messages.add(new SystemMessage(request.systemPrompt()));
    }
    for (Message message : request.messages()) {
      messages.add(toSpringMessage(message));
    }
    return new Prompt(messages, options.build());
  }

  private static org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    if (Message.ROLE_USER.equals(message.role())) {
      return new UserMessage(message.content());
    }
    if (Message.ROLE_TOOL.equals(message.role())) {
      String id = message.toolCallId();
      // 无 id 的工具结果（旧格式历史，或被截断得没了配对的 assistant tool_call）：不发成协议级 tool 消息——
      // 否则 OpenAI 会 400「tool 必须紧跟带 tool_calls 的 assistant」。降级成信息性 user 文本喂给模型。
      if (id == null || id.isBlank()) {
        return new UserMessage("[工具 " + message.toolName() + " 返回] " + message.content());
      }
      return new ToolResponseMessage(
          List.of(new ToolResponseMessage.ToolResponse(id, message.toolName(), message.content())));
    }
    // assistant：带 tool_calls（含 id）才能让下一轮的 tool 结果配上对
    if (message.toolCalls().isEmpty()) {
      return new AssistantMessage(message.content());
    }
    List<AssistantMessage.ToolCall> toolCalls =
        message.toolCalls().stream()
            .map(
                tc ->
                    new AssistantMessage.ToolCall(
                        tc.id() == null ? "" : tc.id(), "function", tc.name(), tc.argumentsJson()))
            .toList();
    return new AssistantMessage(message.content(), Map.of(), toolCalls);
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
              .map(call -> new ToolCallRequest(call.id(), call.name(), call.arguments()))
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
