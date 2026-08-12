package io.oryxos.core.skill;

import java.util.List;

/** Explicit startup dependency result: migration finishes before profile scanning and watchers. */
public record AgentSkillStartupReport(
    List<AgentSkillMigrationService.MigrationResult> migrations, List<SkillBindingIssue> issues) {
  public AgentSkillStartupReport {
    migrations = migrations == null ? List.of() : List.copyOf(migrations);
    issues = issues == null ? List.of() : List.copyOf(issues);
  }
}
