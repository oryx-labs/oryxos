package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillBindingIssue;

public record SkillBindingIssueView(
    String agentName,
    String agentState,
    String entryName,
    String path,
    String type,
    String message) {
  public static SkillBindingIssueView from(SkillBindingIssue issue) {
    return new SkillBindingIssueView(
        issue.agentName(),
        issue.agentState().name(),
        issue.entryName(),
        issue.path().toString(),
        issue.type().name(),
        issue.message());
  }
}
