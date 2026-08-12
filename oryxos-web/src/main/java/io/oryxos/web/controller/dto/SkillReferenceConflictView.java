package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillReference;
import java.util.List;

public record SkillReferenceConflictView(String name, List<ReferenceView> references) {
  public SkillReferenceConflictView {
    references = List.copyOf(references);
  }

  public static SkillReferenceConflictView from(String name, List<SkillReference> references) {
    return new SkillReferenceConflictView(
        name,
        references.stream()
            .map(
                reference ->
                    new ReferenceView(
                        reference.agentName(),
                        reference.state().name(),
                        reference.directoryName(),
                        reference.linkPath().toString()))
            .toList());
  }

  public record ReferenceView(
      String agentName, String state, String directoryName, String linkPath) {}
}
