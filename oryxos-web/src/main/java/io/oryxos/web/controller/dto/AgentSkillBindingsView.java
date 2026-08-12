package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.BindingInspection;
import java.util.List;

public record AgentSkillBindingsView(
    List<BindingView> bindings, List<SkillBindingIssueView> issues) {
  public AgentSkillBindingsView {
    bindings = bindings == null ? List.of() : List.copyOf(bindings);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  public static AgentSkillBindingsView from(BindingInspection inspection) {
    return new AgentSkillBindingsView(
        inspection.bindings().stream()
            .map(
                binding ->
                    new BindingView(
                        binding.name(), binding.description(), binding.skillFile().toString()))
            .toList(),
        inspection.issues().stream().map(SkillBindingIssueView::from).toList());
  }

  public record BindingView(String name, String description, String skillFile) {}
}
