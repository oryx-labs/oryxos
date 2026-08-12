package io.oryxos.core.skill;

/** Read-only boundary used by prompt assembly; implementations must return a fresh scan. */
@FunctionalInterface
public interface AgentSkillBindingReader {
  BindingInspection inspect(String agentName);
}
