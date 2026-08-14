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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Provider 前台（core {@link ProviderService} 契约的 Spring AI 实现）：按 Profile 显式路由到对应
 * ChatModel，完成一次调用并落审计。
 *
 * <p>宪法 II/III：显式 name→ChatModel 映射、调用方式 {@code chatModel.call(new Prompt(...))}、 {@code
 * internalToolExecutionEnabled=false} 关闭框架自动工具执行——工具 schema 只翻译、tool call 原样透传。
 */
public class SpringAiProviderServiceImpl implements ProviderService {

  private static final Logger LOG = LoggerFactory.getLogger(SpringAiProviderServiceImpl.class);

  private final ProviderRegistry registry;
  private final Function<ProviderDef, ChatModel> chatModelBuilder;
  private final ToolSchemaAdapter adapter;
  private final LlmCallAuditor audit;
  // 已建的 ChatModel 缓存：key = provider name，值携带配置指纹（apiKey|baseUrl）。指纹变了原地替换旧条目——
  // 缓存大小恒等于 provider 数，反复改 key/url 不再累积不可回收的旧实例（31 节动态 provider）。
  private final Map<String, CachedModel> cache = new ConcurrentHashMap<>();

  /** 缓存条目：配置指纹 + 已建实例，指纹不变则复用。 */
  private record CachedModel(String fingerprint, ChatModel model) {}

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
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "日志中的 provider 名已经 sanitize() 消去 CR/LF；taint 分析不跨方法追踪该消毒，故局部抑制")
  public ProviderResponse chat(String sessionId, Profile profile, ProviderRequest request) {
    String providerName = profile.provider().name();
    // 宪法 III：仍是按 name 的显式查找，只是从"启动静态 map"变成"运行时注册表 + 按名动态建/缓存"
    ProviderDef def =
        registry.find(providerName).orElseThrow(() -> new ProviderNotFoundException(providerName));
    ChatModel model = resolveModel(def);
    Prompt prompt = buildPrompt(profile, request);
    long startedAt = System.currentTimeMillis();
    ProviderResponse result;
    try {
      ChatResponse response = model.call(prompt);
      result = toProviderResponse(response);
    } catch (RuntimeException e) {
      // 失败也留痕（宪法 V）：先落审计再上抛——只记成功不记失败，一次真实事故就没有痕迹。
      // 审计自身再失败也不许反客为主：上抛的必须是模型调用的真实异常（排障首先看到的是「LLM 调 400」
      // 而非「审计存储抖动」），审计异常挂 suppressed + ERROR 日志独立告警。
      try {
        audit.record(
            sessionId,
            providerName,
            profile.provider().model(),
            null,
            false,
            e.getMessage(),
            System.currentTimeMillis() - startedAt);
      } catch (RuntimeException auditFailure) {
        LOG.error("LLM 调用失败的审计落库也失败（主异常照常上抛）: provider={}", sanitize(providerName), auditFailure);
        e.addSuppressed(auditFailure);
      }
      throw e;
    }
    // 成功审计 fail-open：调用已成功、token 已消耗，审计存储抖动不应让调用方丢掉这次完整回答
    // （宪法 V 约束的是实现上不许省审计，不是拿审计故障牺牲用户请求）；失败走 ERROR 日志独立告警。
    try {
      audit.record(
          sessionId,
          providerName,
          profile.provider().model(),
          result.usage(),
          true,
          null,
          System.currentTimeMillis() - startedAt);
    } catch (RuntimeException auditFailure) {
      LOG.error("成功 LLM 调用的审计落库失败（结果照常返回）: provider={}", sanitize(providerName), auditFailure);
    }
    return result;
  }

  /** 日志参数消毒：去掉换行，防日志伪造（CRLF injection）。 */
  private static String sanitize(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }

  /** 按 provider 名缓存已建的 ChatModel；同名下 key/url 变化即原地重建替换（provider CRUD 改了配置立即生效，旧实例可回收）。 */
  private ChatModel resolveModel(ProviderDef def) {
    String fingerprint = def.apiKey() + "|" + def.baseUrl();
    return cache
        .compute(
            def.name(),
            (name, cached) ->
                cached != null && cached.fingerprint().equals(fingerprint)
                    ? cached
                    : new CachedModel(fingerprint, chatModelBuilder.apply(def)))
        .model();
  }

  private Prompt buildPrompt(Profile profile, ProviderRequest request) {
    OpenAiChatOptions.Builder options =
        OpenAiChatOptions.builder()
            .model(profile.provider().model())
            .internalToolExecutionEnabled(Boolean.FALSE); // 执行权只在 ToolExecutor（17 节）
    if (profile.provider().temperature() != null) {
      options.temperature(profile.provider().temperature());
    }
    List<ToolCallback> callbacks = adapter.toSpringAiTools(request.availableTools());
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
      return ToolResponseMessage.builder()
          .responses(
              List.of(
                  new ToolResponseMessage.ToolResponse(id, message.toolName(), message.content())))
          .build();
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
    return AssistantMessage.builder()
        .content(message.content())
        .properties(Map.of())
        .toolCalls(toolCalls)
        .build();
  }

  private static ProviderResponse toProviderResponse(ChatResponse response) {
    Generation generation = response.getResult();
    AssistantMessage output = generation.getOutput();
    String text = output.getText();
    List<ToolCallRequest> toolCalls =
        output.getToolCalls().stream()
            .map(call -> new ToolCallRequest(call.id(), call.name(), call.arguments()))
            .toList();
    return new ProviderResponse(text, toolCalls, extractUsage(response));
  }

  private static Usage extractUsage(ChatResponse response) {
    if (response.getMetadata().getUsage() == null) {
      return null;
    }
    org.springframework.ai.chat.metadata.Usage usage = response.getMetadata().getUsage();
    return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
  }
}
