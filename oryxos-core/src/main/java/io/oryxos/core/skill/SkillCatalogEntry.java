package io.oryxos.core.skill;

/** Caller-filtered external Skill candidate metadata; visibility is a label, not an ACL. */
public record SkillCatalogEntry(
    String name, String description, Visibility visibility, String source, boolean installed) {
  public enum Visibility {
    PUBLIC,
    PRIVATE
  }
}
