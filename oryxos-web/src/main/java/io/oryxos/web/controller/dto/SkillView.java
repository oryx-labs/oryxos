package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.Skill;

/** GET /skills 视图：从 {@link Skill} 投影出可对外展示的字段（第 32 节）。 */
public record SkillView(String name, String description, String body) {

  public static SkillView from(Skill s) {
    return new SkillView(s.name(), s.description(), s.body());
  }
}
