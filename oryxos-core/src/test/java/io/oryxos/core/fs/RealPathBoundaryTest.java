package io.oryxos.core.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealPathBoundaryTest {

  @TempDir Path temp;

  @Test
  void projectsExistingAndNotYetExistingTargets() throws Exception {
    Path root = Files.createDirectories(temp.resolve("root"));
    Path file = Files.writeString(root.resolve("a.txt"), "x");
    assertEquals(file.toRealPath(), RealPathBoundary.requireWithin(root, file));

    Path future = root.resolve("new/child.txt");
    assertTrue(RealPathBoundary.requireWithin(root, future).startsWith(root.toRealPath()));
  }

  @Test
  void rejectsParentLinkEscapeDanglingAndCycle() throws Exception {
    Path root = Files.createDirectories(temp.resolve("root"));
    Path outside = Files.createDirectories(temp.resolve("outside"));
    Files.createSymbolicLink(root.resolve("escape"), outside);
    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(root, root.resolve("escape/new.txt")));

    Files.createSymbolicLink(root.resolve("second"), outside);
    Files.createSymbolicLink(root.resolve("first"), Path.of("second"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(root, root.resolve("first/secret.txt")));

    Files.createSymbolicLink(root.resolve("dangling"), Path.of("missing"));
    assertThrows(
        UncheckedIOException.class,
        () -> RealPathBoundary.requireWithin(root, root.resolve("dangling")));

    Files.createSymbolicLink(root.resolve("one"), Path.of("two"));
    Files.createSymbolicLink(root.resolve("two"), Path.of("one"));
    assertThrows(
        UncheckedIOException.class,
        () -> RealPathBoundary.requireWithin(root, root.resolve("one")));
  }

  @Test
  void rootMayItselfBeASymlink() throws Exception {
    Path actual = Files.createDirectories(temp.resolve("actual"));
    Path linkedRoot = temp.resolve("linked-root");
    Files.createSymbolicLink(linkedRoot, actual);
    Path target = Files.writeString(actual.resolve("ok.txt"), "ok");
    assertEquals(
        target.toRealPath(),
        RealPathBoundary.requireWithin(linkedRoot, linkedRoot.resolve("ok.txt")));
  }
}
