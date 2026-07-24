package io.oryxos.core.skill;

import java.util.List;

/** Informational dependency declarations; this Feature does not execute gating or installation. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The canonical constructor replaces every List with an immutable List.copyOf.")
public record GatingRequirements(
    List<String> bins, List<String> env, List<String> config, List<String> skills) {

  public GatingRequirements {
    bins = immutable(bins);
    env = immutable(env);
    config = immutable(config);
    skills = immutable(skills);
  }

  public static GatingRequirements empty() {
    return new GatingRequirements(List.of(), List.of(), List.of(), List.of());
  }

  public GatingRequirements enforceLimits() {
    return new GatingRequirements(
        bins, env, config, skills.stream().limit(SkillManifestLimits.MAX_REQUIRED_SKILLS).toList());
  }

  private static List<String> immutable(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
  }
}
