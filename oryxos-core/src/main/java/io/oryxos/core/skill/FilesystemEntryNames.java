package io.oryxos.core.skill;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exact on-disk basename checks that remain reliable on case-insensitive filesystems. */
final class FilesystemEntryNames {

  private FilesystemEntryNames() {}

  static boolean isStoredAs(Path path, String expectedName) throws IOException {
    if (path == null || expectedName == null) {
      return false;
    }
    Path parent = path.getParent();
    if (parent == null) {
      return false;
    }
    try (DirectoryStream<Path> siblings = Files.newDirectoryStream(parent)) {
      for (Path sibling : siblings) {
        boolean sameFile;
        try {
          sameFile = Files.isSameFile(sibling, path);
        } catch (IOException ignored) {
          continue;
        }
        if (sameFile) {
          return expectedName.equals(String.valueOf(sibling.getFileName()));
        }
      }
      return false;
    }
  }
}
