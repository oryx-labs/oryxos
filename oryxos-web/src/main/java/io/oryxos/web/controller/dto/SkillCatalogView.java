package io.oryxos.web.controller.dto;

import io.oryxos.core.skill.SkillCatalogEntry;

public record SkillCatalogView(
    String name, String description, String visibility, String source, boolean installed) {
  public static SkillCatalogView from(SkillCatalogEntry entry) {
    return new SkillCatalogView(
        entry.name(),
        entry.description(),
        entry.visibility().name(),
        entry.source(),
        entry.installed());
  }
}
