package io.oryxos.web.controller.dto;

import java.util.List;

/** POST /agents request: Agent metadata, selected provider/model, and initial Skill bindings. */
public record CreateAgentRequest(
    String name, String description, String provider, String model, List<String> skillBindings) {
  public CreateAgentRequest {
    skillBindings = skillBindings == null ? List.of() : List.copyOf(skillBindings);
  }

  public CreateAgentRequest(String name, String description) {
    this(name, description, null, null, List.of());
  }
}
