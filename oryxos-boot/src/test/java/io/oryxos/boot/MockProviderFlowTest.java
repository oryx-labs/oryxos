package io.oryxos.boot;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.OryxTool;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.PromptBuilder;
import io.oryxos.core.agent.ReActLoop;
import io.oryxos.core.agent.ToolExecutor;
import io.oryxos.core.agent.ToolInvocationAuditor;
import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.LlmCallAuditor;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.session.SessionSummary;
import io.oryxos.memory.MarkdownMemoryStore;
import io.oryxos.memory.MemoryServiceImpl;
import io.oryxos.memory.builtin.MemoryTools;
import io.oryxos.provider.MockChatModel;
import io.oryxos.provider.SpringAiProviderServiceImpl;
import io.oryxos.provider.ToolSchemaAdapter;
import io.oryxos.tool.ToolRegistry;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.controller.MemoryApiController;
import io.oryxos.web.controller.SessionApiController;
import io.oryxos.web.controller.ToolApiController;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 用 mock provider 打通全链路——无 key、无网络、gate 内可跑。
 *
 * <p>只有"模型"是假的（{@link MockChatModel}）：ReActLoop / ToolExecutor / Memory / Session / 审计全部真实。 一次
 * "记住…" 消息应驱动出：两轮 ReAct、一次 save_memory 工具调用（真写 MEMORY.md）、会话累积完整历史；
 * 随后管理台三个只读端点（/sessions、/memory、/tools） 应能查到这条链路留下的数据。
 */
class MockProviderFlowTest {

  private static final String AGENT = "mock-agent";
  private static final String SESSION_ID = "mock:default:mock-agent";

  @Test
  @DisplayName("mock provider 打通全链路_react_tool_memory_conversation_并被console查到")
  void mockProvider_drivesFullChain_andConsoleSeesData(@TempDir Path root) throws Exception {
    // —— 真实组件装配（唯一的假是 MockChatModel）——
    MarkdownMemoryStore store = new MarkdownMemoryStore(root);
    MemoryService memory = new MemoryServiceImpl(store);

    ToolRegistry registry = new ToolRegistry();
    registry.registerAnnotated(new MemoryTools(memory)); // save_memory / recall_memory
    Map<String, OryxTool> tools = registry.asMap();

    LlmCallAuditor llmAuditor = mock(LlmCallAuditor.class);
    ToolInvocationAuditor toolAuditor = mock(ToolInvocationAuditor.class);

    ProviderService provider =
        new SpringAiProviderServiceImpl(
            Map.of("mock", new MockChatModel()), new ToolSchemaAdapter(), llmAuditor);

    PromptBuilder promptBuilder =
        new PromptBuilder(new ContextLoader(root), tools, memory, Clock.systemDefaultZone());
    ToolExecutor toolExecutor = new ToolExecutor(tools, toolAuditor);
    ReActLoop loop = new ReActLoop(promptBuilder, provider, toolExecutor);

    Profile profile =
        new Profile(
            AGENT,
            "mock 自测 Agent",
            new Profile.Identity("mock小欧", "你是一个测试助手。"),
            new Profile.ProviderRef("mock", "mock-model", null),
            List.of("save_memory", "recall_memory"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Profile.Settings.defaults());
    ProfileRegistry profileRegistry = new ProfileRegistry(Map.of(AGENT, profile));
    SessionManager sessionManager = mock(SessionManager.class);
    AgentService agent = new AgentService(profileRegistry, loop, sessionManager);

    // —— 跑一次"记住…"对话 ——
    Session session = new Session(SESSION_ID, AGENT);
    String reply = agent.process(session, "记住：我在北京，怕冷");

    // 对话执行：非空最终答复
    assertFalse(reply.isBlank(), "应有非空最终答复");
    // ReAct 执行：两轮（第一轮调工具、第二轮收尾）→ 两条 assistant 消息
    assertEquals(2, countRole(session, Message.ROLE_ASSISTANT), "ReAct 应跑两轮");
    // tool 调用：恰一次 save_memory
    List<Message> toolMsgs = session.messages().stream().filter(m -> isTool(m)).toList();
    assertEquals(1, toolMsgs.size(), "应恰调用一次工具");
    assertEquals("save_memory", toolMsgs.get(0).toolName());
    // 对话执行：user + 2×assistant + 1×tool = 4 条完整历史
    assertEquals(4, session.messages().size(), "会话历史应完整累积");
    // 记忆记录：save_memory 真写了 MEMORY.md
    assertTrue(store.load().contains("北京"), "记忆文件应记下事实");
    // 审计：llm_calls 恰 2、tool_invocations 恰 1（宪法 V）
    verify(llmAuditor, times(2)).record(any(), any(), any(), any(), anyBoolean(), any(), anyLong());
    verify(toolAuditor, times(1))
        .record(any(), any(), any(), any(), anyBoolean(), any(), anyLong());

    // —— 管理台三个只读端点，读同一批服务，应能查到链路留下的数据 ——
    when(sessionManager.listRecent(anyInt()))
        .thenReturn(
            List.of(
                new SessionSummary(
                    SESSION_ID,
                    AGENT,
                    "mock",
                    "default",
                    "active",
                    Instant.now(),
                    Instant.now(),
                    session.messages().size())));
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new MemoryApiController(memory),
                new ToolApiController(tools),
                new SessionApiController(agent, sessionManager))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value(containsString("北京")));
    mvc.perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[*].name").value(hasItem("save_memory")));
    mvc.perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].sessionId").value(SESSION_ID))
        .andExpect(jsonPath("$.data[0].messageCount").value(4));
  }

  private static long countRole(Session session, String role) {
    return session.messages().stream().filter(m -> role.equals(m.role())).count();
  }

  private static boolean isTool(Message message) {
    return Message.ROLE_TOOL.equals(message.role());
  }
}
