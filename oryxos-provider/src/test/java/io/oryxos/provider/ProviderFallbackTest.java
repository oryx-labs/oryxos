package io.oryxos.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

/** 023 US1：切换序列语义——主败备成/全败/不可切/候选跳过/零声明回归/流式边界/无健康记忆 + 审计每尝试一条。 */
class ProviderFallbackTest {

  private ChatModel primary;
  private ChatModel backup;
  private LlmCallAuditor audit;
  private SpringAiProviderServiceImpl service;

  @BeforeEach
  void setUp() {
    primary = mock(ChatModel.class);
    backup = mock(ChatModel.class);
    audit = mock(LlmCallAuditor.class);
    ProviderRegistry registry = mock(ProviderRegistry.class);
    when(registry.find(anyString())).thenReturn(Optional.empty());
    when(registry.find("primary"))
        .thenReturn(Optional.of(new ProviderDef("primary", "key", "https://p", null)));
    when(registry.find("backup"))
        .thenReturn(Optional.of(new ProviderDef("backup", "key", "https://b", null)));
    Map<String, ChatModel> byName = Map.of("primary", primary, "backup", backup);
    service =
        new SpringAiProviderServiceImpl(
            registry,
            def -> byName.get(def.name()),
            new ToolSchemaAdapter(),
            audit,
            (p, m) -> Optional.empty());
  }

  private static Profile profileWithFallback(Profile.ProviderRef.FallbackRef... fallbacks) {
    return new Profile(
        "fb-agent",
        null,
        null,
        new Profile.ProviderRef("primary", "p-model", null, List.of(fallbacks)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        null);
  }

  private static ProviderRequest request() {
    return new ProviderRequest("sys", List.of(), List.of());
  }

  private static ChatResponse reply(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private static BadRequestException badRequest() {
    return BadRequestException.builder().headers(Headers.builder().build()).build();
  }

  @Test
  void 主败备成_返回备结果且备调用携带备model() {
    when(primary.call(any(Prompt.class))).thenThrow(new OpenAIIoException("connect refused"));
    when(backup.call(any(Prompt.class))).thenReturn(reply("备用回复"));

    ProviderResponse response =
        service.chat(
            "s-1",
            profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model")),
            request());

    assertThat(response.text()).isEqualTo("备用回复");
    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(backup).call(prompt.capture());
    // R2 陷阱钉死：备用调用必须携带备用模型名，不是主声明的 p-model
    assertThat(((OpenAiChatOptions) prompt.getValue().getOptions()).getModel())
        .isEqualTo("b-model");
  }

  @Test
  void 全部候选失败_上抛最后异常() {
    when(primary.call(any(Prompt.class))).thenThrow(new OpenAIIoException("primary down"));
    OpenAIIoException lastError = new OpenAIIoException("backup down");
    when(backup.call(any(Prompt.class))).thenThrow(lastError);

    assertThatThrownBy(
            () ->
                service.chat(
                    "s-1",
                    profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model")),
                    request()))
        .isSameAs(lastError); // 最后一次错误，不吞不换
  }

  @Test
  void 业务性失败400_不切换直接上抛() {
    BadRequestException error = badRequest();
    when(primary.call(any(Prompt.class))).thenThrow(error);

    assertThatThrownBy(
            () ->
                service.chat(
                    "s-1",
                    profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model")),
                    request()))
        .isSameAs(error);
    verify(backup, never()).call(any(Prompt.class)); // FR-003：无第二次尝试
  }

  @Test
  void 候选未注册_跳过直达下一候选() {
    when(primary.call(any(Prompt.class))).thenThrow(new OpenAIIoException("down"));
    when(backup.call(any(Prompt.class))).thenReturn(reply("第三候选接住"));

    ProviderResponse response =
        service.chat(
            "s-1",
            profileWithFallback(
                new Profile.ProviderRef.FallbackRef("ghost", "g-model"), // 未注册：跳过不落审计
                new Profile.ProviderRef.FallbackRef("backup", "b-model")),
            request());

    assertThat(response.text()).isEqualTo("第三候选接住");
    verify(audit, never())
        .record(any(), any(), eq("ghost"), any(), any(), any(), anyBoolean(), any(), anyLong());
  }

  @Test
  void 零fallback声明_行为与现状一致() {
    OpenAIIoException error = new OpenAIIoException("down");
    when(primary.call(any(Prompt.class))).thenThrow(error);

    assertThatThrownBy(() -> service.chat("s-1", profileWithFallback(), request())).isSameAs(error);
    // 单次调用单条审计（失败）——SC-002 回归锚点
    verify(audit)
        .record(
            eq("s-1"),
            eq("fb-agent"),
            eq("primary"),
            eq("p-model"),
            isNull(),
            isNull(),
            eq(false),
            any(),
            anyLong());
  }

  @Test
  void 流式_首片段前失败可切换_客户端无感知() {
    when(primary.stream(any(Prompt.class)))
        .thenReturn(Flux.error(new OpenAIIoException("down before first token")));
    when(backup.stream(any(Prompt.class))).thenReturn(Flux.just(reply("备"), reply("用")));

    StringBuilder streamed = new StringBuilder();
    ProviderResponse response =
        service.chatStream(
            "s-1",
            profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model")),
            request(),
            streamed::append);

    assertThat(response.text()).isEqualTo("备用");
    assertThat(streamed.toString()).isEqualTo("备用"); // 无重复输出、无主残片
  }

  @Test
  void 流式_已出token后失败_不切换按现状收尾() {
    OpenAIIoException midStream = new OpenAIIoException("mid-stream cut");
    // error 延迟 300ms 订阅：保证首 chunk 先被消费端拿到（onToken 已回调）后错误才到达——
    // 同批投递时 BlockingIterable 会让 error 抢先于排队 chunk（019 已知特性），那种情况客户端
    // 实际没收到内容、切换反而是安全的，正是本实现「以真实送达为准」的判定依据
    when(primary.stream(any(Prompt.class)))
        .thenReturn(
            Flux.just(reply("半截"))
                .concatWith(
                    Flux.<ChatResponse>error(midStream)
                        .delaySubscription(java.time.Duration.ofMillis(300))));

    assertThatThrownBy(
            () ->
                service.chatStream(
                    "s-1",
                    profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model")),
                    request(),
                    delta -> {}))
        .isSameAs(midStream); // FR-007：内容已流出，重试必重复输出
    verify(backup, never()).stream(any(Prompt.class));
  }

  @Test
  void 连续两次调用_第二次仍先尝试主_无健康记忆() {
    when(primary.call(any(Prompt.class)))
        .thenThrow(new OpenAIIoException("第一次挂"))
        .thenReturn(reply("主恢复了"));
    when(backup.call(any(Prompt.class))).thenReturn(reply("备用回复"));
    Profile profile = profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model"));

    assertThat(service.chat("s-1", profile, request()).text()).isEqualTo("备用回复");
    assertThat(service.chat("s-1", profile, request()).text()).isEqualTo("主恢复了"); // FR-004：每次独立从主开始

    var order = inOrder(primary, backup, primary);
    order.verify(primary).call(any(Prompt.class));
    order.verify(backup).call(any(Prompt.class));
    order.verify(primary).call(any(Prompt.class));
  }

  @Test
  void 审计每尝试一条_主败备成恰两条且参数如实() {
    when(primary.call(any(Prompt.class))).thenThrow(new OpenAIIoException("down"));
    when(backup.call(any(Prompt.class))).thenReturn(reply("备用回复"));

    service.chat(
        "s-1",
        profileWithFallback(new Profile.ProviderRef.FallbackRef("backup", "b-model")),
        request());

    // SC-003 单测面：失败主 + 成功备各一条，provider/model 按 attempt 如实
    verify(audit)
        .record(
            eq("s-1"),
            eq("fb-agent"),
            eq("primary"),
            eq("p-model"),
            isNull(),
            isNull(),
            eq(false),
            any(),
            anyLong());
    verify(audit)
        .record(
            eq("s-1"),
            eq("fb-agent"),
            eq("backup"),
            eq("b-model"),
            any(),
            any(),
            eq(true),
            isNull(),
            anyLong());
  }
}
