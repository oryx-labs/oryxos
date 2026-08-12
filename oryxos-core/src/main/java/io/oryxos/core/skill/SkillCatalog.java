package io.oryxos.core.skill;

import java.util.List;

/** Port for a caller-filtered external public/private Skill catalog. */
@FunctionalInterface
public interface SkillCatalog {
  List<SkillCatalogEntry> query(String query, SkillCatalogEntry.Visibility visibility);
}
