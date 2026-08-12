package io.oryxos.core.skill;

import java.util.List;

/** Immutable result of one live scan of an Agent's local Skill links. */
public record BindingInspection(
    List<BoundSkillDescriptor> bindings, List<SkillBindingIssue> issues) {
  public BindingInspection {
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
