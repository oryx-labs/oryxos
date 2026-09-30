package io.oryxos.core.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AgentAwareFlowNodeHandlerTest {

  private static FlowRun dummyRun() {
    Instant t = Instant.parse("2026-09-26T04:00:00Z");
    return new FlowRun(
        "r1", "flow", "1", "", FlowRunState.RUNNING, "n1", "n1", "{}", "{}", null, 0, t, t);
  }

  @Test
  @DisplayName("no runner: AGENT node keeps DefaultFlowNodeHandler echo")
  void withoutRunner_fallsThroughToEcho() {
    FlowNode node =
        new FlowNode(
            "writer",
            FlowNodeType.AGENT,
            "docs-writer",
            Map.of("topic", new FlowPort("topic", FlowPortType.STRING, null, true)),
            Map.of("message", new FlowPort("message", FlowPortType.STRING, null, true)),
            List.of());
    FlowNodeHandler handler = new AgentAwareFlowNodeHandler(new DefaultFlowNodeHandler());
    FlowNodeOutcome out = handler.execute(node, Map.of("topic", "ship it"), dummyRun());
    assertTrue(out.succeeded());
    assertEquals("ship it", out.outputs().get("message"));
  }

  @Test
  @DisplayName("runner present: AGENT ref invokes runner and maps reply to message port")
  void withRunner_invokesAgentAndMapsReply() {
    AtomicReference<String> seenAgent = new AtomicReference<>();
    AtomicReference<String> seenMsg = new AtomicReference<>();
    FlowAgentRunner runner =
        (agent, msg, caps) -> {
          seenAgent.set(agent);
          seenMsg.set(msg);
          return "draft:" + msg;
        };
    FlowNode node =
        new FlowNode(
            "writer",
            FlowNodeType.AGENT,
            "docs-writer",
            Map.of("message", new FlowPort("message", FlowPortType.STRING, null, true)),
            Map.of("message", new FlowPort("message", FlowPortType.STRING, null, true)),
            List.of());
    FlowNodeHandler handler = new AgentAwareFlowNodeHandler(new DefaultFlowNodeHandler(), runner);
    FlowNodeOutcome out = handler.execute(node, Map.of("message", "write the README"), dummyRun());
    assertTrue(out.succeeded());
    assertEquals("docs-writer", seenAgent.get());
    assertTrue(seenMsg.get().contains("write the README"));
    assertEquals("draft:write the README", out.outputs().get("message"));
  }

  @Test
  @DisplayName("AGENT without ref fails loud")
  void missingRef_fails() {
    FlowNode node =
        new FlowNode(
            "writer",
            FlowNodeType.AGENT,
            null,
            Map.of(),
            Map.of("message", new FlowPort("message", FlowPortType.STRING, null, true)),
            List.of());
    FlowNodeHandler handler =
        new AgentAwareFlowNodeHandler(new DefaultFlowNodeHandler(), (a, m, caps) -> "x");
    FlowNodeOutcome out = handler.execute(node, Map.of(), dummyRun());
    assertTrue(out.failed());
    assertTrue(out.error().contains("ref"));
  }

  @Test
  @DisplayName("composeUserMessage prefers message then appends extras")
  void composeMessage_prefersPrimaryPort() {
    Map<String, Object> in = new LinkedHashMap<>();
    in.put("message", "main task");
    in.put("priority", "high");
    String msg = AgentAwareFlowNodeHandler.composeUserMessage(in);
    assertTrue(msg.startsWith("main task"));
    assertTrue(msg.contains("priority=high"));
  }

  @Test
  @DisplayName("HUMAN nodes still wait via delegate")
  void humanStillWaiting() {
    FlowNode node =
        new FlowNode("approve", FlowNodeType.HUMAN, null, Map.of(), Map.of(), List.of());
    FlowNodeHandler handler =
        new AgentAwareFlowNodeHandler(
            new DefaultFlowNodeHandler(), (a, m, caps) -> "should-not-run");
    FlowNodeOutcome out = handler.execute(node, Map.of(), dummyRun());
    assertTrue(out.waiting());
  }
}
