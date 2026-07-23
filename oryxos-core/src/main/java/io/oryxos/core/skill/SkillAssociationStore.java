package io.oryxos.core.skill;

import io.oryxos.core.agent.AgentName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Atomic filesystem store for public Skill to Agent associations. */
public final class SkillAssociationStore {

  private static final long MAX_FILE_BYTES = 1024 * 1024;
  private static final String AGENT_SEPARATOR = ",";
  private final Path file;

  public SkillAssociationStore(Path oryxosRoot) {
    this.file =
        oryxosRoot.toAbsolutePath().normalize().resolve("skill-associations.txt").normalize();
  }

  public synchronized List<String> agentsFor(String skillName) {
    String skill = requireSkillName(skillName);
    return List.copyOf(read().getOrDefault(skill, Set.of()));
  }

  public synchronized Set<String> skillsFor(String agentName) {
    String agent = AgentName.parse(agentName).value();
    Set<String> result = new TreeSet<>();
    read()
        .forEach(
            (skill, agents) -> {
              if (agents.contains(agent)) {
                result.add(skill);
              }
            });
    return Set.copyOf(result);
  }

  public synchronized void associate(String skillName, String agentName) {
    String skill = requireSkillName(skillName);
    String agent = AgentName.parse(agentName).value();
    Map<String, Set<String>> associations = read();
    Set<String> agents = new TreeSet<>(associations.getOrDefault(skill, Set.of()));
    if (agents.add(agent)) {
      associations.put(skill, agents);
      write(associations);
    }
  }

  public synchronized void dissociate(String skillName, String agentName) {
    String skill = requireSkillName(skillName);
    String agent = AgentName.parse(agentName).value();
    Map<String, Set<String>> associations = read();
    Set<String> agents = new TreeSet<>(associations.getOrDefault(skill, Set.of()));
    if (agents.remove(agent)) {
      if (agents.isEmpty()) {
        associations.remove(skill);
      } else {
        associations.put(skill, agents);
      }
      write(associations);
    }
  }

  public synchronized void removeAgent(String agentName) {
    String agent = AgentName.parse(agentName).value();
    Map<String, Set<String>> associations = read();
    boolean changed = false;
    for (String skill : List.copyOf(associations.keySet())) {
      Set<String> agents = new TreeSet<>(associations.get(skill));
      if (agents.remove(agent)) {
        changed = true;
        if (agents.isEmpty()) {
          associations.remove(skill);
        } else {
          associations.put(skill, agents);
        }
      }
    }
    if (changed) {
      write(associations);
    }
  }

  private Map<String, Set<String>> read() {
    Map<String, Set<String>> result = new TreeMap<>();
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return result;
    }
    try {
      if (Files.isSymbolicLink(file)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > MAX_FILE_BYTES) {
        throw new IllegalStateException("Skill association store is unavailable");
      }
      for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        int separator = line.indexOf('=');
        if (separator <= 0) {
          throw new IllegalStateException("Skill association store is invalid");
        }
        String skill = requireSkillName(line.substring(0, separator));
        Set<String> agents = new TreeSet<>();
        String value = line.substring(separator + 1);
        if (!value.isBlank()) {
          for (String agent : value.split(AGENT_SEPARATOR, -1)) {
            agents.add(AgentName.parse(agent).value());
          }
        }
        if (!agents.isEmpty()) {
          result.put(skill, agents);
        }
      }
      return result;
    } catch (IOException | IllegalArgumentException error) {
      throw new IllegalStateException("Skill association store is invalid", error);
    }
  }

  private void write(Map<String, Set<String>> associations) {
    Path parent = Objects.requireNonNull(file.getParent(), "association store parent");
    try {
      Files.createDirectories(parent);
      if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        throw new IllegalStateException("Skill association store directory is unavailable");
      }
      List<String> lines = new ArrayList<>();
      new TreeMap<>(associations)
          .forEach(
              (skill, agents) -> {
                if (!agents.isEmpty()) {
                  lines.add(skill + "=" + String.join(AGENT_SEPARATOR, new TreeSet<>(agents)));
                }
              });
      Path temp = Files.createTempFile(parent, ".skill-associations-", ".tmp");
      try {
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException | UnsupportedOperationException error) {
      throw new IllegalStateException("Skill associations could not be saved atomically", error);
    }
  }

  private static String requireSkillName(String value) {
    if (!SkillMetadata.isValidName(value)) {
      throw new IllegalArgumentException("Invalid Skill name: " + value);
    }
    return value;
  }
}
