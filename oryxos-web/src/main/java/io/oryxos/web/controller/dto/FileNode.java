package io.oryxos.web.controller.dto;

import java.util.List;

/** Workspace tree node. Symbolic links are always leaves and are never recursively followed. */
public record FileNode(
    String name,
    String path,
    String type,
    List<FileNode> children,
    String linkTarget,
    String linkStatus) {

  public FileNode {
    children = children == null ? List.of() : List.copyOf(children);
  }

  public static FileNode file(String name, String path) {
    return new FileNode(name, path, "file", List.of(), null, null);
  }

  public static FileNode dir(String name, String path, List<FileNode> children) {
    return new FileNode(name, path, "dir", children, null, null);
  }

  public static FileNode link(String name, String path, String target, String status) {
    return new FileNode(name, path, "link", List.of(), target, status);
  }
}
