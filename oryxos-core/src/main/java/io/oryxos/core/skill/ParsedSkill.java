package io.oryxos.core.skill;

import java.util.Objects;

/** Canonical parser result. Prompt content is loaded only during validation or explicit L2 read. */
public record ParsedSkill(SkillManifest manifest, String promptContent) {
  public ParsedSkill {
    manifest = Objects.requireNonNull(manifest, "manifest");
    promptContent = Objects.requireNonNull(promptContent, "promptContent");
  }
}
