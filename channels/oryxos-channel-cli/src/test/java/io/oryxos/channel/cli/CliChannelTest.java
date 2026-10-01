package io.oryxos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.agent.StreamListener;
import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.PrincipalContext;
import io.oryxos.core.auth.Role;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CliChannel 循环健壮性：单轮异常（provider 400/超时等）不终结整个终端会话。 */
class CliChannelTest {

  @AfterEach
  void clearPrincipal() {
    PrincipalContext.clear();
  }

  @Test
  @DisplayName("一轮 process 抛异常_打印原因并继续下一轮（对齐飞书/企微 handler 保活口径）")
  void processFailureDoesNotEndSession() {
    AgentService agentService = mock(AgentService.class);
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(sessionManager.getOrCreate("cli", "u", "p")).thenReturn(session);
    when(agentService.process(eq(session), eq("第一轮"), any(StreamListener.class)))
        .thenThrow(new IllegalStateException("provider 400"));
    when(agentService.process(eq(session), eq("第二轮"), any(StreamListener.class)))
        .thenReturn("正常回复");

    InputStream oldIn = System.in;
    PrintStream oldOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setIn(new ByteArrayInputStream("第一轮\n第二轮\n/quit\n".getBytes(StandardCharsets.UTF_8)));
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));

      new CliChannel(agentService, sessionManager).run("p", "u");
    } finally {
      System.setIn(oldIn);
      System.setOut(oldOut);
    }

    String output = captured.toString(StandardCharsets.UTF_8);
    assertThat(output).contains("[本轮出错: provider 400]");
    assertThat(output).contains("正常回复");
    assertThat(output).contains("再见。");
  }

  @Test
  @DisplayName("533：process 期间 PrincipalContext 为 OS 用户，结束后清除")
  void processInstallsUserPrincipalAndClearsAfter() {
    AgentService agentService = mock(AgentService.class);
    SessionManager sessionManager = mock(SessionManager.class);
    Session session = mock(Session.class);
    when(sessionManager.getOrCreate("cli", "alice", "p")).thenReturn(session);
    AtomicReference<Principal> seen = new AtomicReference<>();
    when(agentService.process(eq(session), eq("hi"), any(StreamListener.class)))
        .thenAnswer(
            inv -> {
              seen.set(PrincipalContext.current());
              return "ok";
            });

    CliChannel channel = new CliChannel(agentService, sessionManager);
    channel.setRunRoles(Set.of(Role.EDITOR));

    InputStream oldIn = System.in;
    PrintStream oldOut = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    try {
      System.setIn(new ByteArrayInputStream("hi\n/quit\n".getBytes(StandardCharsets.UTF_8)));
      System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
      channel.run("p", "alice");
    } finally {
      System.setIn(oldIn);
      System.setOut(oldOut);
    }

    assertThat(seen.get()).isNotNull();
    assertThat(seen.get().id()).isEqualTo("alice");
    assertThat(seen.get().hasRole(Role.EDITOR)).isTrue();
    assertThat(PrincipalContext.current()).isNull();
  }
}
