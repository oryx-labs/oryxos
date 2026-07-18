package io.oryxos.web.controller.dto;

import java.util.List;

/** 工作区目录树节点：type ∈ {dir, file}；dir 有 children，file 为叶子。 */
public record FileNode(String name, String path, String type, List<FileNode> children) {

  public FileNode {
    children = children == null ? List.of() : List.copyOf(children);
  }

  public static FileNode file(String name, String path) {
    return new FileNode(name, path, "file", List.of());
  }

  public static FileNode dir(String name, String path, List<FileNode> children) {
    return new FileNode(name, path, "dir", children);
  }
}
