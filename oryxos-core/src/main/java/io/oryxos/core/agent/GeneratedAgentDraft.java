package io.oryxos.core.agent;

import java.util.List;
import java.util.Map;

/** Transient authoring sidecar; the binding lists are never persisted in Agent files. */
public record GeneratedAgentDraft(
    Map<String, String> files,
    List<String> requiredSkills,
    List<String> suggestedSkills,
    List<String> bindingSkills) {
  public GeneratedAgentDraft {
    files = files == null ? Map.of() : Map.copyOf(files);
    requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
    suggestedSkills = suggestedSkills == null ? List.of() : List.copyOf(suggestedSkills);
    bindingSkills = bindingSkills == null ? List.of() : List.copyOf(bindingSkills);
  }
}
