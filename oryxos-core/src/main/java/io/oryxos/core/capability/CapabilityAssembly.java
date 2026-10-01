package io.oryxos.core.capability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Partitioned capability overlay for one Agent turn (Direction C assembly). Empty buckets mean "do
 * not constrain that kind"; non-empty buckets replace / filter that kind for the turn.
 */
public final class CapabilityAssembly {

  private final List<String> tools;
  private final List<String> mcpServers;
  private final List<String> skills;
  private final List<String> knowledge;
  private final List<String> memory;

  private CapabilityAssembly(
      List<String> tools,
      List<String> mcpServers,
      List<String> skills,
      List<String> knowledge,
      List<String> memory) {
    this.tools = List.copyOf(tools);
    this.mcpServers = List.copyOf(mcpServers);
    this.skills = List.copyOf(skills);
    this.knowledge = List.copyOf(knowledge);
    this.memory = List.copyOf(memory);
  }

  public static CapabilityAssembly empty() {
    return new CapabilityAssembly(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  public static CapabilityAssembly from(List<CapabilityRef> refs) {
    if (refs == null || refs.isEmpty()) {
      return empty();
    }
    Set<String> tools = new LinkedHashSet<>();
    Set<String> mcp = new LinkedHashSet<>();
    Set<String> skills = new LinkedHashSet<>();
    Set<String> knowledge = new LinkedHashSet<>();
    Set<String> memory = new LinkedHashSet<>();
    for (CapabilityRef ref : refs) {
      if (ref == null) {
        continue;
      }
      switch (ref.kind()) {
        case TOOL -> tools.add(ref.name());
        case MCP -> mcp.add(ref.name());
        case SKILL -> skills.add(ref.name());
        case KNOWLEDGE -> knowledge.add(ref.name());
        case MEMORY -> memory.add(ref.name());
      }
    }
    return new CapabilityAssembly(
        new ArrayList<>(tools),
        new ArrayList<>(mcp),
        new ArrayList<>(skills),
        new ArrayList<>(knowledge),
        new ArrayList<>(memory));
  }

  public boolean isEmpty() {
    return tools.isEmpty()
        && mcpServers.isEmpty()
        && skills.isEmpty()
        && knowledge.isEmpty()
        && memory.isEmpty();
  }

  public boolean constrainsTools() {
    return !tools.isEmpty();
  }

  public boolean constrainsMcp() {
    return !mcpServers.isEmpty();
  }

  public boolean constrainsSkills() {
    return !skills.isEmpty();
  }

  public boolean constrainsKnowledge() {
    return !knowledge.isEmpty();
  }

  public boolean constrainsMemory() {
    return !memory.isEmpty();
  }

  public List<String> tools() {
    return tools;
  }

  public List<String> mcpServers() {
    return mcpServers;
  }

  public List<String> skills() {
    return skills;
  }

  public List<String> knowledge() {
    return knowledge;
  }

  public List<String> memory() {
    return memory;
  }

  /** True when {@code name} is allowed under a constraining skill bucket (or unconstrained). */
  public boolean allowsSkill(String name) {
    Objects.requireNonNull(name, "name");
    return !constrainsSkills() || skills.contains(name);
  }

  public boolean allowsKnowledge(String name) {
    Objects.requireNonNull(name, "name");
    return !constrainsKnowledge() || knowledge.contains(name);
  }
}
