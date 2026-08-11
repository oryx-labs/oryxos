package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.OryxTool;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

/** 课件《第16节》验收 harness：ProviderServiceTest——三个中文名关键回归原样落地。 */
class ProviderServiceTest {

  private ChatModel deepseek;
  private ChatModel kimi;
  private LlmCallAuditor audit;
  private ProviderService service;

  @BeforeEach
  void setUp() {
    deepseek = mock(ChatModel.class);
    kimi = mock(ChatModel.class);
    audit = mock(LlmCallAuditor.class);
    io.oryxos.core.provider.ProviderRegistry registry =
        mock(io.oryxos.core.provider.ProviderRegistry.class);
    when(registry.find(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(java.util.Optional.empty());
    when(registry.find("deepseek"))
        .thenReturn(
            java.util.Optional.of(
                new io.oryxos.core.provider.ProviderDef("deepseek", "key", "https://x", null)));
    when(registry.find("kimi"))
        .thenReturn(
            java.util.Optional.of(
                new io.oryxos.core.provider.ProviderDef("kimi", "key", "https://x", null)));
    java.util.Map<String, ChatModel> byName = java.util.Map.of("deepseek", deepseek, "kimi", kimi);
    service =
        new SpringAiProviderServiceImpl(
            registry, def -> byName.get(def.name()), new ToolSchemaAdapter(), audit);
  }

  private static Profile profileUsing(String providerName) {
    return profileUsing(providerName, null);
  }

  private static Profile profileUsing(String providerName, Double temperature) {
    return new Profile(
        "test-agent",
        null,
        null,
        new Profile.ProviderRef(providerName, "model-x", temperature),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static ChatResponse textResponse(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static OryxTool httpGetTool() {
    OryxTool tool = mock(OryxTool.class);
    when(tool.getName()).thenReturn("http_get");
    when(tool.getDescription()).thenReturn("发起一个 HTTP GET 请求");
    when(tool.getInputSchema()).thenReturn("{\"type\":\"object\"}");
    return tool;
  }

  @Test
  void 按名路由_两个provider不串台() {
    when(kimi.call(any(Prompt.class))).thenReturn(textResponse("你好"));

    service.chat("s-1", profileUsing("kimi"), ProviderRequest.of("hi"));

    verify(kimi, times(1)).call(any(Prompt.class)); // 调的是 kimi
    verify(deepseek, never()).call(any(Prompt.class)); // deepseek 一次都没被碰——"不串台"的直接证据
  }

  @Test
  void 带工具schema调用_请求里关闭了自动执行() {
    when(deepseek.call(any(Prompt.class))).thenReturn(textResponse("ok"));

    service.chat(
        "s-1", profileUsing("deepseek"), new ProviderRequest("查天气", List.of(httpGetTool())));

    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
    verify(deepseek).call(captor.capture());
    OpenAiChatOptions options = (OpenAiChatOptions) captor.getValue().getOptions();
    assertFalse(options.getInternalToolExecutionEnabled()); // 坑二的回归：一旦有人改回自动执行，这里立刻红
    assertFalse(options.getToolCallbacks().isEmpty()); // 翻译过的 schema 确实带上了
    assertEquals("http_get", options.getToolCallbacks().get(0).getToolDefinition().name());
  }

  @Test
  void 未知provider名_抛异常且信息含名字() {
    ProviderNotFoundException ex =
        assertThrows(
            ProviderNotFoundException.class,
            () -> service.chat("s-1", profileUsing("nonexistent"), ProviderRequest.of("hi")));

    assertTrue(ex.getMessage().contains("nonexistent")); // 点名，不许静默换家
    verify(deepseek, never()).call(any(Prompt.class));
    verify(kimi, never()).call(any(Prompt.class));
  }

  @Test
  void model与temperature来自Profile_缺省temperature不设置() {
    when(deepseek.call(any(Prompt.class))).thenReturn(textResponse("ok"));
    ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);

    service.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));
    verify(deepseek).call(captor.capture());
    OpenAiChatOptions defaults = (OpenAiChatOptions) captor.getValue().getOptions();
    assertEquals("model-x", defaults.getModel());
    assertNull(defaults.getTemperature()); // 缺省不传，用 provider 侧默认（D6）

    service.chat("s-1", profileUsing("deepseek", 0.7), ProviderRequest.of("hi"));
    verify(deepseek, times(2)).call(captor.capture());
    OpenAiChatOptions withTemp = (OpenAiChatOptions) captor.getValue().getOptions();
    assertEquals(0.7, withTemp.getTemperature());
  }

  @Test
  void 调用失败_审计必须留下success为false的记录() {
    when(deepseek.call(any(Prompt.class))).thenThrow(new RuntimeException("connect timeout"));

    assertThrows(
        RuntimeException.class,
        () -> service.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"))); // 异常继续上抛

    verify(audit)
        .record(
            eq("s-1"),
            eq("deepseek"),
            eq("model-x"),
            isNull(),
            eq(false),
            contains("timeout"),
            anyLong()); // 但审计先落了：success=false + 原因
  }

  @Test
  void 调用成功_审计恰好落一条success为true的记录() {
    when(deepseek.call(any(Prompt.class))).thenReturn(textResponse("你好"));

    service.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));

    verify(audit, times(1))
        .record(eq("s-1"), eq("deepseek"), eq("model-x"), any(), eq(true), isNull(), anyLong());
  }

  @Test
  void 成功调用的审计失败_结果照常返回不当模型失败() {
    when(deepseek.call(any(Prompt.class))).thenReturn(textResponse("你好"));
    doThrow(new IllegalStateException("audit unavailable"))
        .when(audit)
        .record(eq("s-1"), eq("deepseek"), eq("model-x"), any(), eq(true), isNull(), anyLong());

    // LLM 已成功、token 已消耗：审计存储抖动不能让调用方丢掉这次完整回答（fail-open + ERROR 日志）
    ProviderResponse response =
        service.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));

    assertEquals("你好", response.text());
    verify(deepseek, times(1)).call(any(Prompt.class)); // 不重试模型
    verify(audit, never())
        .record(eq("s-1"), eq("deepseek"), eq("model-x"), isNull(), eq(false), any(), anyLong());
  }

  @Test
  void 调用失败且审计也失败_上抛的是模型异常审计异常挂suppressed() {
    RuntimeException modelFailure = new RuntimeException("LLM 调 400");
    when(deepseek.call(any(Prompt.class))).thenThrow(modelFailure);
    IllegalStateException auditFailure = new IllegalStateException("audit unavailable");
    doThrow(auditFailure)
        .when(audit)
        .record(eq("s-1"), eq("deepseek"), eq("model-x"), isNull(), eq(false), any(), anyLong());

    RuntimeException thrown =
        assertThrows(
            RuntimeException.class,
            () -> service.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi")));

    // 排障首先看到的必须是模型的真实错误，审计抖动只是附带信息
    assertEquals(modelFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    assertEquals(auditFailure, thrown.getSuppressed()[0]);
  }

  @Test
  void 模型想调工具_请求被原样透传_本模块零执行() {
    AssistantMessage withToolCall =
        AssistantMessage.builder()
            .content("")
            .properties(Map.of())
            .toolCalls(
                List.of(
                    new AssistantMessage.ToolCall(
                        "id-1", "function", "http_get", "{\"url\":\"x\"}")))
            .build();
    when(deepseek.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(withToolCall))));

    ProviderResponse response =
        service.chat(
            "s-1", profileUsing("deepseek"), new ProviderRequest("查天气", List.of(httpGetTool())));

    assertTrue(response.hasToolCalls());
    assertEquals("http_get", response.toolCalls().get(0).name());
    assertEquals("{\"url\":\"x\"}", response.toolCalls().get(0).argumentsJson()); // 原样，未执行
  }

  @Test
  void provider配置变更_ChatModel缓存原地替换不累积() {
    io.oryxos.core.provider.ProviderRegistry registry =
        mock(io.oryxos.core.provider.ProviderRegistry.class);
    java.util.concurrent.atomic.AtomicReference<io.oryxos.core.provider.ProviderDef> current =
        new java.util.concurrent.atomic.AtomicReference<>(
            new io.oryxos.core.provider.ProviderDef("deepseek", "key-1", "https://a", null));
    when(registry.find("deepseek")).thenAnswer(inv -> java.util.Optional.of(current.get()));
    java.util.concurrent.atomic.AtomicInteger builds =
        new java.util.concurrent.atomic.AtomicInteger();
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(textResponse("ok"));
    ProviderService cachedService =
        new SpringAiProviderServiceImpl(
            registry,
            def -> {
              builds.incrementAndGet();
              return model;
            },
            new ToolSchemaAdapter(),
            audit);

    cachedService.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));
    cachedService.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));
    assertEquals(1, builds.get()); // 同配置复用，不重建

    current.set(new io.oryxos.core.provider.ProviderDef("deepseek", "key-2", "https://b", null));
    cachedService.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));
    assertEquals(2, builds.get()); // 改了 key/url → 重建

    current.set(new io.oryxos.core.provider.ProviderDef("deepseek", "key-1", "https://a", null));
    cachedService.chat("s-1", profileUsing("deepseek"), ProviderRequest.of("hi"));
    assertEquals(3, builds.get()); // 换回旧配置也重建——旧条目已被替换而非累积保留
  }
}
