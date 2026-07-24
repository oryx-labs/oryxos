package io.oryxos.web.controller.dto;

import java.util.List;

/** POST /agents 请求体：Agent 名、描述，以及创建时必须关联的公共 Skill。 */
public record CreateAgentRequest(String name, String description, List<String> skills) {

  public CreateAgentRequest {
    skills = skills == null ? List.of() : List.copyOf(skills);
  }
}
