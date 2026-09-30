package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.capability.CapabilityRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentAwareCapabilityAssemblyTest {

  private static FlowRun dummyRun() {
    Instant t = Instant.parse("2026-09-28T00:00:00Z");
    return new FlowRun(
        "r1", "flow", "1", "", FlowRunState.RUNNING, "n1", "n1", "{}", "{}", null, 0, t, t);
  }

  @Test
  @DisplayName("AGENT node forwards capabilities to runner")
  void forwardsCapabilities() {
    AtomicReference<List<CapabilityRef>> seen = new AtomicReference<>();
    FlowAgentRunner runner =
        (agentName, userMessage, capabilities) -> {
          seen.set(capabilities);
          return "capped:" + capabilities.size();
        };
    FlowNode node =
        new FlowNode(
            "n1",
            FlowNodeType.AGENT,
            "writer",
            Map.of(),
            Map.of("message", new FlowPort("message", FlowPortType.STRING, null, true)),
            List.of(),
            null,
            null,
            List.of(CapabilityRef.tool("web_search"), CapabilityRef.skill("summarizer")));
    FlowNodeOutcome out =
        new AgentAwareFlowNodeHandler(new DefaultFlowNodeHandler(), runner)
            .execute(node, Map.of("message", "hi"), dummyRun());
    assertTrue(out.succeeded());
    assertEquals("capped:2", out.outputs().get("message"));
    assertEquals(2, seen.get().size());
  }
}
