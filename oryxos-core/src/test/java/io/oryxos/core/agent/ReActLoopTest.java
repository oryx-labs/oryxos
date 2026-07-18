package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.ToolResult;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.provider.ProviderRequest;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.provider.ToolCallRequest;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 课件《第17节》验收 harness：ReActLoopTest——循环只做调度：转圈、判停、累积。 */
class ReActLoopTest {

  private static final ToolCallRequest HTTP_GET_CALL =
      new ToolCallRequest("http_get", "{\"url\":\"https://wttr.in\"}");

  private PromptBuilder promptBuilder;
  private ProviderService providerService;
  private ToolExecutor toolExecutor;
  private ReActLoop loop;
  private Session session;

  @BeforeEach
  void setUp() {
    promptBuilder = mock(PromptBuilder.class);
    when(promptBuilder.build(any(), any())).thenReturn(ProviderRequest.of("prompt"));
    providerService = mock(ProviderService.class);
    toolExecutor = mock(ToolExecutor.class);
    loop = new ReActLoop(promptBuilder, providerService, toolExecutor);
    session = new Session("s-1", "ops-agent");
  }

  private Profile profileWithMaxIterations(int maxIterations) {
    return new Profile(
        "ops-agent",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of("http_get"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        new Profile.Settings(maxIterations, 20));
  }

  private static ProviderResponse finalAnswer(String text) {
    return new ProviderResponse(text, List.of(), null);
  }

  private static ProviderResponse responseWithToolCall(ToolCallRequest... calls) {
    return new ProviderResponse(null, List.of(calls), null);
  }

  @Test
  @DisplayName("无工具调用_一轮收尾且零工具执行")
  void noToolCallFinishesInOneIteration() {
    when(providerService.chat(any(), any(), any())).thenReturn(finalAnswer("今天穿短袖"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    assertEquals("今天穿短袖", reply);
    verify(providerService, times(1)).chat(eq("s-1"), any(), any());
    verify(toolExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("有工具调用_执行并回填进下一轮收尾")
  void toolCallIsExecutedAndFedBackToNextIteration() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(responseWithToolCall(HTTP_GET_CALL))
        .thenReturn(finalAnswer("晴 28 度，建议短袖"));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("晴，28°C"));

    String reply = loop.run(session, "查天气穿衣", profileWithMaxIterations(10));

    assertEquals("晴 28 度，建议短袖", reply);
    verify(providerService, times(2)).chat(eq("s-1"), any(), any());
    verify(toolExecutor, times(1)).execute(eq("s-1"), eq(HTTP_GET_CALL));
    // 执行发生在两次模型调用之间：想→做→再想
    InOrder order = inOrder(providerService, toolExecutor);
    order.verify(providerService).chat(any(), any(), any());
    order.verify(toolExecutor).execute(any(), any());
    order.verify(providerService).chat(any(), any(), any());
  }

  @Test
  @DisplayName("一轮多个工具调用_逐个顺序执行")
  void multipleToolCallsInOneIterationExecuteSequentially() {
    ToolCallRequest second = new ToolCallRequest("read_file", "{\"path\":\"/tmp/a\"}");
    when(providerService.chat(any(), any(), any()))
        .thenReturn(responseWithToolCall(HTTP_GET_CALL, second))
        .thenReturn(finalAnswer("done"));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("ok"));

    loop.run(session, "干活", profileWithMaxIterations(10));

    InOrder order = inOrder(toolExecutor);
    order.verify(toolExecutor).execute("s-1", HTTP_GET_CALL);
    order.verify(toolExecutor).execute("s-1", second);
  }

  @Test
  @DisplayName("每轮响应和工具结果都累积进 Session（坑三回归）")
  void everyResponseAndToolResultIsAccumulatedIntoSession() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(responseWithToolCall(HTTP_GET_CALL))
        .thenReturn(finalAnswer("最终答复"));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("晴，28°C"));

    loop.run(session, "查天气", profileWithMaxIterations(10));

    List<String> roles = session.messages().stream().map(Message::role).toList();
    // 完整回放：用户消息 → 第一轮响应 → 工具结果 → 第二轮响应
    assertEquals(List.of("user", "assistant", "tool", "assistant"), roles);
    assertEquals("晴，28°C", session.messages().get(2).content());
    assertEquals("最终答复", session.messages().get(3).content());
  }

  @Test
  @DisplayName("模型一直要调工具_转满最大轮数强制停")
  void modelKeepsRequestingTools_forceStopAtMaxIterations() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(responseWithToolCall(HTTP_GET_CALL)); // 每轮都要调工具，永不收敛
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("ok"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    verify(providerService, times(10)).chat(any(), any(), any()); // 恰好 10 轮，一轮不多
    assertTrue(reply.contains("达到最大轮数"));
  }

  @Test
  @DisplayName("最大轮数按 Agent 配置生效（5 轮即停）")
  void maxIterationsIsPerProfileNotHardcoded() {
    when(providerService.chat(any(), any(), any())).thenReturn(responseWithToolCall(HTTP_GET_CALL));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("ok"));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(5));

    verify(providerService, times(5)).chat(any(), any(), any());
    assertTrue(reply.contains("达到最大轮数"));
  }

  @Test
  @DisplayName("响应既无文本也无工具调用_按空串收尾")
  void nullTextWithoutToolCallsFinishesWithEmptyReply() {
    when(providerService.chat(any(), any(), any())).thenReturn(finalAnswer(null));

    String reply = loop.run(session, "hi", profileWithMaxIterations(10));

    assertEquals("", reply);
    verify(providerService, times(1)).chat(any(), any(), any());
  }

  @Test
  @DisplayName("工具执行失败_失败结果回填循环继续不中断")
  void failedToolResultIsFedBackAndLoopContinues() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(responseWithToolCall(HTTP_GET_CALL))
        .thenReturn(finalAnswer("拿不到天气，建议看窗外"));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.error("connect timeout", true));

    String reply = loop.run(session, "查天气", profileWithMaxIterations(10));

    assertEquals("拿不到天气，建议看窗外", reply);
    // 失败结果同样进历史，模型下一轮能看到失败原因
    assertTrue(session.messages().get(2).content().contains("connect timeout"));
  }
}
