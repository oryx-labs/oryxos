package io.oryxos.core.fs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/** Symlink-aware path projection used by every workspace file boundary. */
public final class RealPathBoundary {

  private RealPathBoundary() {}

  /**
   * Resolves the nearest existing ancestor to its real path and appends any not-yet-existing
   * suffix. Dangling links, link cycles and unresolvable ancestors fail closed.
   */
  public static Projection project(Path input) {
    if (input == null) {
      throw new IllegalArgumentException("路径不能为空");
    }
    Path absolute = input.toAbsolutePath().normalize();
    Path cursor = absolute;
    Deque<Path> suffix = new ArrayDeque<>();
    while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
      Path name = cursor.getFileName();
      if (name != null) {
        suffix.addFirst(name);
      }
      cursor = cursor.getParent();
    }
    if (cursor == null) {
      throw new IllegalArgumentException("路径没有可解析的已存在祖先: " + absolute);
    }
    Path ancestorReal;
    try {
      ancestorReal = cursor.toRealPath();
    } catch (IOException e) {
      throw new UncheckedIOException("真实路径解析失败: " + cursor, e);
    }
    Path projected = ancestorReal;
    for (Path segment : suffix) {
      projected = projected.resolve(segment.toString());
    }
    return new Projection(absolute, ancestorReal, projected.normalize());
  }

  /** Returns the projected real path when it is inside the projected root, otherwise rejects it. */
  public static Path requireWithin(Path root, Path target) {
    Path rootReal = project(root).projectedReal();
    Path targetReal = project(target).projectedReal();
    if (!targetReal.startsWith(rootReal)) {
      throw new IllegalArgumentException("真实路径越界，拒绝访问: " + target);
    }
    return targetReal;
  }

  public static boolean isWithin(Path root, Path target) {
    try {
      requireWithin(root, target);
      return true;
    } catch (IllegalArgumentException | UncheckedIOException e) {
      return false;
    }
  }

  public record Projection(
      Path absoluteNormalized, Path existingAncestorReal, Path projectedReal) {}
}
