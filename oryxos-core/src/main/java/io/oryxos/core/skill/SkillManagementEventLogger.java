package io.oryxos.core.skill;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Single structured domain-event sink for every public Skill mutation. */
public final class SkillManagementEventLogger {

  private static final Logger LOG = LoggerFactory.getLogger("skill.management");

  public void record(
      String action,
      String result,
      String skill,
      String agent,
      List<String> affectedAgents,
      String reasonCode) {
    LOG.atInfo()
        .addKeyValue("event", "skill.management")
        .addKeyValue("action", safe(action))
        .addKeyValue("result", safe(result))
        .addKeyValue("skill", safe(skill))
        .addKeyValue("agent", safe(agent))
        .addKeyValue("affectedAgents", affectedAgents == null ? List.of() : affectedAgents)
        .addKeyValue("reasonCode", safe(reasonCode))
        .log("SKILL_MANAGEMENT");
  }

  private static String safe(String value) {
    return value == null ? "" : value.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
