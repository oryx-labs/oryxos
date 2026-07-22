package io.oryxos.web.controller.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/** PUT /agents/{agentName}/skills/{skillName}: enabled is required and must be a JSON boolean. */
public record SetSkillEnabledRequest(
    @Schema(type = "boolean", requiredMode = Schema.RequiredMode.REQUIRED) JsonNode enabled) {

  /**
   * Reject Jackson's usual string/number coercions: the wire value itself must be true or false.
   */
  public boolean requireEnabled() {
    if (enabled == null || !enabled.isBoolean()) {
      throw new IllegalArgumentException("enabled is required and must be a JSON boolean");
    }
    return enabled.booleanValue();
  }
}
