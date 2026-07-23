package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AgentView;
import io.oryxos.web.error.ResourceNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Associates global Skills with existing Agents through AGENT.md frontmatter. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "Core-stage management endpoints are internal-network APIs; lifecycle is a shared Spring"
            + " collaborator.")
@RestController
@RequestMapping("/api/v1/skills/{skillName}/agents")
public final class SkillAssociationApiController {

  private final AgentLifecycleService lifecycle;

  public SkillAssociationApiController(AgentLifecycleService lifecycle) {
    this.lifecycle = lifecycle;
  }

  @PutMapping("/{agentName}")
  public ApiResponse<AgentView> associate(
      @PathVariable String skillName, @PathVariable String agentName) {
    requireAgent(agentName);
    return ApiResponse.ok(
        AgentView.from(lifecycle.setSkillAssociation(agentName, skillName, true)));
  }

  @DeleteMapping("/{agentName}")
  public ApiResponse<AgentView> dissociate(
      @PathVariable String skillName, @PathVariable String agentName) {
    requireAgent(agentName);
    return ApiResponse.ok(
        AgentView.from(lifecycle.setSkillAssociation(agentName, skillName, false)));
  }

  private void requireAgent(String agentName) {
    if (lifecycle.get(agentName).isEmpty()) {
      throw new ResourceNotFoundException("Agent 不存在: " + agentName);
    }
  }
}
