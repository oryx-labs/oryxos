package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Message;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 课件《第17节》验收 harness：AgentServiceTest——统一入口与 ProfileContext 生命周期。 */
class AgentServiceTest {

  private Profile profile;
  private ReActLoop reActLoop;
  private SessionManager sessionManager;
  private AgentService agentService;
  private Session session;

  @BeforeEach
  void setUp() {
    profile =
        new Profile(
            "ops-agent",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Profile.Settings.defaults());
    reActLoop = mock(ReActLoop.class);
    sessionManager = mock(SessionManager.class);
    agentService =
        new AgentService(
            new ProfileRegistry(Map.of("ops-agent", profile)),
            reActLoop,
            sessionManager,
            mock(io.oryxos.core.memory.MemoryService.class));
    session = new Session("s-1", "ops-agent");
  }

  @AfterEach
  void tearDown() {
    ProfileContext.clear();
  }

  @Test
  @DisplayName("处理期间 ProfileContext 可取到当前 Profile")
  void profileContextIsVisibleDuringProcessing() {
    AtomicReference<Profile> seenDuringRun = new AtomicReference<>();
    when(reActLoop.run(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              seenDuringRun.set(ProfileContext.current()); // 工具执行时靠它知道"当前是哪个 Agent"
              return "ok";
            });

    agentService.process(session, "hi");

    assertEquals(profile, seenDuringRun.get());
  }

  @Test
  @DisplayName("处理中抛异常_ProfileContext也必须被清掉")
  void processThrowsException_profileContextMustBeCleared() {
    when(reActLoop.run(any(), any(), any())).thenThrow(new RuntimeException("boom"));

    assertThrows(RuntimeException.class, () -> agentService.process(session, "hi"));

    assertNull(ProfileContext.current()); // finally 没清，下一个复用此线程的请求会拿到别人的 Profile
  }

  @Test
  @DisplayName("正常结束后 Session 被持久化且返回循环结果")
  void sessionIsSavedAfterNormalCompletion() {
    when(reActLoop.run(any(), any(), any())).thenReturn("最终答复");

    String reply = agentService.process(session, "hi");

    assertEquals("最终答复", reply);
    verify(sessionManager).saveIfUnchanged(session, List.of());
    assertNull(ProfileContext.current()); // 正常路径同样清干净
  }

  @Test
  @DisplayName("异常路径不持久化 Session（Clarification 2）")
  void sessionIsNotSavedWhenProcessingThrows() {
    when(reActLoop.run(any(), any(), any())).thenThrow(new RuntimeException("boom"));

    assertThrows(RuntimeException.class, () -> agentService.process(session, "hi"));

    verify(sessionManager, never()).saveIfUnchanged(any(), any());
  }

  @Test
  @DisplayName("Profile 不存在时点名报错")
  void unknownProfileFailsWithNamedError() {
    Session orphan = new Session("s-2", "no-such-agent");

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> agentService.process(orphan, "hi"));

    assertTrue(ex.getMessage().contains("no-such-agent"), "报错必须点名缺失的 Profile");
  }

  @Test
  @DisplayName("达到最大迭代上限时抛 AgentMaxIterationsExceededException，且 Session 已保存（对话保留供排查）")
  void maxIterationsExceeded_throwsExceptionAndSavesSession() {
    when(reActLoop.run(any(), any(), any())).thenReturn(ReActLoop.MAX_ITERATIONS_REPLY);

    AgentMaxIterationsExceededException ex =
        assertThrows(
            AgentMaxIterationsExceededException.class, () -> agentService.process(session, "hi"));

    assertEquals(ReActLoop.MAX_ITERATIONS_REPLY, ex.getMessage());
    verify(sessionManager).saveIfUnchanged(session, List.of()); // 对话现场保留，供排查为什么不收敛
    assertNull(ProfileContext.current());
  }

  @Test
  @DisplayName("进入会话锁后重读最新快照并基于原始历史做条件保存")
  void processingReloadsLatestSessionInsideLockAndSavesConditionally() {
    Session stale = new Session("s-1", "ops-agent");
    Session latest = new Session("s-1", "ops-agent");
    latest.appendUser("已经保存的上一轮");
    List<Message> expectedMessages = latest.messages();
    when(sessionManager.get("s-1")).thenReturn(Optional.of(latest));
    when(reActLoop.run(any(), any(), any())).thenReturn("ok");

    agentService.process(stale, "下一轮");

    verify(reActLoop).run(latest, "下一轮", profile);
    verify(sessionManager).saveIfUnchanged(latest, expectedMessages);
    verify(sessionManager, never()).save(any());
  }

  @Test
  @DisplayName("保存前按 Agent 的 maxHistoryTurns 截断持久历史")
  void persistedHistoryIsTrimmedToProfileLimit() {
    Profile boundedProfile =
        new Profile(
            "ops-agent",
            null,
            null,
            new Profile.ProviderRef("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new Profile.Settings(10, 2));
    AgentService boundedService =
        new AgentService(
            new ProfileRegistry(Map.of("ops-agent", boundedProfile)),
            reActLoop,
            sessionManager,
            mock(io.oryxos.core.memory.MemoryService.class));
    Session latest = new Session("s-1", "ops-agent");
    latest.appendUser("问题1");
    latest.appendUser("问题2");
    latest.appendUser("问题3");
    List<Message> baseline = latest.messages();
    when(sessionManager.get("s-1")).thenReturn(Optional.of(latest));
    when(reActLoop.run(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              invocation.<Session>getArgument(0).appendUser("问题4");
              return "ok";
            });

    boundedService.process(new Session("s-1", "ops-agent"), "问题4");

    assertEquals(List.of("问题3", "问题4"), latest.messages().stream().map(Message::content).toList());
    verify(sessionManager).saveIfUnchanged(latest, baseline);
  }

  @Test
  @DisplayName("无状态处理不保存会话且每次使用独立审计标识")
  void statelessProcessingDoesNotSaveSessionAndUsesUniqueExecutionIds() {
    when(reActLoop.run(any(), any(), any())).thenReturn("无状态答复");

    assertEquals("无状态答复", agentService.processStateless("ops-agent", "first"));
    assertEquals("无状态答复", agentService.processStateless("ops-agent", "second"));

    ArgumentCaptor<Session> sessions = ArgumentCaptor.forClass(Session.class);
    verify(reActLoop, org.mockito.Mockito.times(2)).run(sessions.capture(), any(), eq(profile));
    String firstExecutionId = sessions.getAllValues().get(0).sessionId();
    String secondExecutionId = sessions.getAllValues().get(1).sessionId();
    assertTrue(firstExecutionId.startsWith("invoke-exec:"));
    assertTrue(secondExecutionId.startsWith("invoke-exec:"));
    assertNotEquals(firstExecutionId, secondExecutionId);
    verify(sessionManager, never()).save(any());
    assertNull(ProfileContext.current());
  }

  @Test
  @DisplayName("无状态处理达到迭代上限仍抛异常且不保存会话")
  void statelessMaxIterationsThrowsWithoutSavingSession() {
    when(reActLoop.run(any(), any(), any())).thenReturn(ReActLoop.MAX_ITERATIONS_REPLY);

    assertThrows(
        AgentMaxIterationsExceededException.class,
        () -> agentService.processStateless("ops-agent", "hi"));

    verify(sessionManager, never()).save(any());
    assertNull(ProfileContext.current());
  }
}
