package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillDescriptor;
import java.util.List;

/** Public Skill detail including editable SKILL.md content and Agent choices. */
public record PublicSkillDetailView(
    SkillDetailView skill, String content, List<String> agentNames, List<String> availableAgents) {

  public PublicSkillDetailView {
    agentNames = agentNames == null ? List.of() : List.copyOf(agentNames);
    availableAgents = availableAgents == null ? List.of() : List.copyOf(availableAgents);
  }

  public static PublicSkillDetailView from(
      SkillDescriptor descriptor,
      String content,
      List<String> agentNames,
      List<String> availableAgents) {
    return new PublicSkillDetailView(
        SkillDetailView.from(descriptor), content, agentNames, availableAgents);
  }
}
