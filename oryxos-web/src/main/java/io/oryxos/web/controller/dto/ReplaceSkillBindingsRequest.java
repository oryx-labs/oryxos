package io.oryxos.web.controller.dto;

import java.util.List;

public record ReplaceSkillBindingsRequest(List<String> skills) {
  public ReplaceSkillBindingsRequest {
    skills = skills == null ? List.of() : List.copyOf(skills);
  }
}
