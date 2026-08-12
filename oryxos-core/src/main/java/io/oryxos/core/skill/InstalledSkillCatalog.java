package io.oryxos.core.skill;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Default catalog adapter exposing validated local installations as PUBLIC/local candidates. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification =
        "SkillRegistry is the injected live installation index and must be observed by reference.")
public final class InstalledSkillCatalog implements SkillCatalog {

  private final SkillRegistry registry;

  public InstalledSkillCatalog(SkillRegistry registry) {
    this.registry = registry;
  }

  @Override
  public List<SkillCatalogEntry> query(String query, SkillCatalogEntry.Visibility visibility) {
    String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    if (visibility == SkillCatalogEntry.Visibility.PRIVATE) {
      return List.of();
    }
    return registry.all().stream()
        .filter(
            skill ->
                needle.isEmpty()
                    || skill.name().toLowerCase(Locale.ROOT).contains(needle)
                    || skill.description().toLowerCase(Locale.ROOT).contains(needle))
        .sorted(Comparator.comparing(Skill::name))
        .map(
            skill ->
                new SkillCatalogEntry(
                    skill.name(),
                    skill.description(),
                    SkillCatalogEntry.Visibility.PUBLIC,
                    "local-installed",
                    true))
        .toList();
  }
}
