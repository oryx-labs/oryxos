package io.oryxos.core.flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Flow node handler that delegates {@link FlowNodeType#AGENT} nodes to a {@link FlowAgentRunner}
 * when present; otherwise falls through to the wrapped handler (typically {@link
 * DefaultFlowNodeHandler} echo/stub behavior).
 *
 * <p>Message composition: prefer input ports {@code message}, {@code text}, then {@code plan}; any
 * remaining ports are appended as a compact {@code key=value} block.
 */
public final class AgentAwareFlowNodeHandler implements FlowNodeHandler {

  private final FlowNodeHandler delegate;
  private final FlowAgentRunner runner;

  public AgentAwareFlowNodeHandler(FlowNodeHandler delegate, FlowAgentRunner runner) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.runner = runner;
  }

  /** No runner: identical to wrapping {@link DefaultFlowNodeHandler} alone. */
  public AgentAwareFlowNodeHandler(FlowNodeHandler delegate) {
    this(delegate, null);
  }

  @Override
  public FlowNodeOutcome execute(FlowNode node, Map<String, Object> inputs, FlowRun run) {
    Objects.requireNonNull(node, "node");
    if (node.type() != FlowNodeType.AGENT || runner == null) {
      return delegate.execute(node, inputs, run);
    }
    String agentName = node.ref();
    if (agentName == null || agentName.isBlank()) {
      return FlowNodeOutcome.failed("AGENT node missing ref (agent name)");
    }
    String userMessage = composeUserMessage(inputs);
    try {
      String reply = runner.run(agentName, userMessage, node.capabilities());
      if (reply == null) {
        reply = "";
      }
      return FlowNodeOutcome.succeeded(mapOutputs(node, reply, inputs));
    } catch (RuntimeException e) {
      String msg = e.getMessage();
      return FlowNodeOutcome.failed(
          msg == null || msg.isBlank() ? "AGENT run failed: " + e.getClass().getSimpleName() : msg);
    }
  }

  static String composeUserMessage(Map<String, Object> inputs) {
    Map<String, Object> in = inputs == null ? Map.of() : inputs;
    for (String key : new String[] {"message", "text", "plan"}) {
      Object v = in.get(key);
      if (v != null && !String.valueOf(v).isBlank()) {
        String primary = String.valueOf(v).strip();
        String extras = extrasBlock(in, key);
        return extras.isEmpty() ? primary : primary + "\n\n" + extras;
      }
    }
    if (in.isEmpty()) {
      return "";
    }
    return extrasBlock(in, null);
  }

  private static String extrasBlock(Map<String, Object> in, String skipKey) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Object> e : in.entrySet()) {
      if (skipKey != null && skipKey.equals(e.getKey())) {
        continue;
      }
      if (e.getValue() == null) {
        continue;
      }
      if (!sb.isEmpty()) {
        sb.append('\n');
      }
      sb.append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.toString();
  }

  static Map<String, Object> mapOutputs(FlowNode node, String reply, Map<String, Object> inputs) {
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> in = inputs == null ? Map.of() : inputs;
    Map<String, FlowPort> ports = node.outputs();
    if (ports.isEmpty()) {
      out.put("message", reply);
      out.put("text", reply);
      return out;
    }
    boolean placed = false;
    for (String port : ports.keySet()) {
      if ("message".equals(port)
          || "text".equals(port)
          || "plan".equals(port)
          || "result".equals(port)) {
        out.put(port, reply);
        placed = true;
      } else if (in.containsKey(port)) {
        out.put(port, in.get(port));
      } else {
        out.put(port, null);
      }
    }
    if (!placed) {
      // Prefer first declared output for the reply when ports are custom-named.
      String first = ports.keySet().iterator().next();
      out.put(first, reply);
    }
    return out;
  }
}
