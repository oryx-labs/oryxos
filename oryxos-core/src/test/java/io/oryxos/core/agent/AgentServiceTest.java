package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    verify(sessionManager).save(session);
    assertNull(ProfileContext.current()); // 正常路径同样清干净
  }

  @Test
  @DisplayName("异常路径不持久化 Session（Clarification 2）")
  void sessionIsNotSavedWhenProcessingThrows() {
    when(reActLoop.run(any(), any(), any())).thenThrow(new RuntimeException("boom"));

    assertThrows(RuntimeException.class, () -> agentService.process(session, "hi"));

    verify(sessionManager, never()).save(any());
  }

  @Test
  @DisplayName("Profile 不存在时点名报错")
  void unknownProfileFailsWithNamedError() {
    Session orphan = new Session("s-2", "no-such-agent");

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> agentService.process(orphan, "hi"));

    assertTrue(ex.getMessage().contains("no-such-agent"), "报错必须点名缺失的 Profile");
  }
}
