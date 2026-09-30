package io.oryxos.core.flow;

import io.oryxos.core.capability.CapabilityRef;
import java.util.List;

/**
 * Runs one Agent turn for a Flow {@link FlowNodeType#AGENT} node. Boot/runtime injects this (e.g.
 * {@code AgentService#processStateless}); core Flow tests keep the default echo handler when the
 * runner is absent.
 */
@FunctionalInterface
public interface FlowAgentRunner {

  /**
   * @param agentName profile / agent directory name ({@link FlowNode#ref()})
   * @param userMessage composed from node input ports
   * @param capabilities Direction C assembly overlay; empty = no overlay
   * @return assistant reply text
   */
  String run(String agentName, String userMessage, List<CapabilityRef> capabilities);

  /** Convenience for callers without capability overlay. */
  default String run(String agentName, String userMessage) {
    return run(agentName, userMessage, List.of());
  }
}
