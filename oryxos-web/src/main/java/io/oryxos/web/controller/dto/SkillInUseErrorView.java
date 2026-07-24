package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillInUseException;
import java.util.List;

public record SkillInUseErrorView(String reasonCode, String skillName, List<String> linkedAgents) {
  public static SkillInUseErrorView from(SkillInUseException error) {
    return new SkillInUseErrorView(error.reasonCode(), error.skillName(), error.linkedAgents());
  }
}
