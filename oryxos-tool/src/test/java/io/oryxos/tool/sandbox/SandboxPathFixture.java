package io.oryxos.tool.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SandboxPathFixture {

  private final Path allowed;
  private final Path outside;

  SandboxPathFixture(Path temp) throws IOException {
    this.allowed = Files.createDirectories(temp.resolve("allowed"));
    this.outside = Files.createDirectories(temp.resolve("outside"));
  }

  Path allowed() {
    return allowed;
  }

  Path outside() {
    return outside;
  }

  Path parentEscape() throws IOException {
    Path link = allowed.resolve("escape");
    Files.createSymbolicLink(link, outside);
    return link;
  }

  Path dangling() throws IOException {
    Path link = allowed.resolve("dangling");
    Files.createSymbolicLink(link, Path.of("missing"));
    return link;
  }

  Path multiHopEscape() throws IOException {
    Files.createSymbolicLink(allowed.resolve("second"), outside);
    Path first = allowed.resolve("first");
    Files.createSymbolicLink(first, Path.of("second"));
    return first;
  }

  Path[] cycle() throws IOException {
    Path one = allowed.resolve("one");
    Path two = allowed.resolve("two");
    Files.createSymbolicLink(one, Path.of("two"));
    Files.createSymbolicLink(two, Path.of("one"));
    return new Path[] {one, two};
  }
}
