package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillDescriptor;
import java.util.List;

/** Public Skill summary plus its current Agent associations. */
public record PublicSkillSummaryView(SkillSummaryView skill, List<String> agentNames) {

  public PublicSkillSummaryView {
    agentNames = agentNames == null ? List.of() : List.copyOf(agentNames);
  }

  public static PublicSkillSummaryView from(SkillDescriptor descriptor, List<String> agentNames) {
    return new PublicSkillSummaryView(SkillSummaryView.from(descriptor), agentNames);
  }
}
