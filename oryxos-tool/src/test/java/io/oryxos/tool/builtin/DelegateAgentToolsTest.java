package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.agent.ProfileContext;
import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.profile.Profile;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DelegateAgentToolsTest {

  @AfterEach
  void clearContexts() {
    ProfileContext.clear();
    ToolExecutionContext.clear();
  }

  private static Profile boss() {
    return new Profile(
        "boss",
        "outer",
        null,
        new Profile.ProviderRef("p", "m", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }

  @Test
  @DisplayName("delegates and restores ProfileContext")
  void restoresProfile() {
    ProfileContext.set(boss());
    ToolExecutionContext.setAgentName("boss");
    AtomicInteger calls = new AtomicInteger();
    DelegateAgentTools tools =
        new DelegateAgentTools(
            (agent, msg, caps) -> {
              calls.incrementAndGet();
              assertEquals("helper", agent);
              ProfileContext.clear();
              ToolExecutionContext.clear();
              return "done:" + msg;
            },
            true,
            3);
    assertEquals("done:hi", tools.delegateAgent("helper", "hi"));
    assertEquals(1, calls.get());
    assertEquals("boss", ProfileContext.current().name());
    assertEquals("boss", ToolExecutionContext.agentName());
  }

  @Test
  @DisplayName("rejects self-delegation")
  void rejectsSelf() {
    ToolExecutionContext.setAgentName("solo");
    DelegateAgentTools tools = new DelegateAgentTools((a, m, c) -> "x", true, 3);
    assertThrows(IllegalArgumentException.class, () -> tools.delegateAgent("solo", "task"));
  }

  @Test
  @DisplayName("caps nest depth")
  void capsDepth() {
    DelegateAgentTools[] box = new DelegateAgentTools[1];
    box[0] =
        new DelegateAgentTools(
            (agent, msg, caps) -> box[0].delegateAgent("next-" + agent, msg + "!"), true, 2);
    ToolExecutionContext.setAgentName("root");
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> box[0].delegateAgent("child", "go"));
    assertTrue(ex.getMessage().contains("depth"));
  }

  @Test
  @DisplayName("disabled fails loud")
  void disabled() {
    DelegateAgentTools tools = new DelegateAgentTools((a, m, c) -> "x", false, 3);
    assertThrows(IllegalStateException.class, () -> tools.delegateAgent("a", "m"));
  }
}
