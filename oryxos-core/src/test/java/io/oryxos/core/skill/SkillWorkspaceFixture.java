package io.oryxos.core.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SkillWorkspaceFixture {

  private final Path root;

  SkillWorkspaceFixture(Path root) {
    this.root = root;
  }

  Path agent(String name, String extraFrontmatter) throws IOException {
    Path directory = Files.createDirectories(root.resolve("agents").resolve(name));
    Files.writeString(
        directory.resolve("AGENT.md"),
        "---\nname: " + name + "\n" + extraFrontmatter + "---\nbody\n");
    return directory;
  }

  Path skill(String name, String description) throws IOException {
    Path directory = Files.createDirectories(root.resolve("skills").resolve(name));
    Files.writeString(
        directory.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\nBODY-" + name);
    return directory;
  }

  Path bind(String agent, String skill) throws IOException {
    Path links = Files.createDirectories(root.resolve("agents").resolve(agent).resolve("skills"));
    Path link = links.resolve(skill);
    Files.createSymbolicLink(link, Path.of("../../../skills").resolve(skill));
    return link;
  }
}
