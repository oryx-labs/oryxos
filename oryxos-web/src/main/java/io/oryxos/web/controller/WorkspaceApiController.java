package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.FileNode;
import io.oryxos.web.controller.dto.WriteFileRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区文件浏览器（第 30 节）：列 {@code .oryxos/agents/} 与 {@code .oryxos/archive/} 的目录树、读写文件文本。
 *
 * <p>唯一安全要点——**防目录穿越**：{@code file}/{@code writeFile} 把 path 解析成绝对路径 {@code normalize()} 后必须 {@code
 * startsWith(oryxosRoot)}，越界即 400。编辑到某个 Agent 的 {@code AGENT.md} 时走 {@link
 * AgentLifecycleService#update} 写入并即时重注册（macOS WatchService 不监听子目录内文件变更，必须显式重注册）；其余文件直接写盘。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "PATH_TRAVERSAL_IN", "PATH_TRAVERSAL_OUT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway). path 已做 normalize()+startsWith(oryxosRoot) 白名单校验，越界 400——这正是防目录穿越的正解。lifecycle 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceApiController {

  private static final String AGENT_FILE = "AGENT.md";

  private final Path oryxosRoot;
  private final AgentLifecycleService lifecycle;

  public WorkspaceApiController(
      @org.springframework.beans.factory.annotation.Value("${oryxos.root:.oryxos}")
          String oryxosRoot,
      AgentLifecycleService lifecycle) {
    this.oryxosRoot = Path.of(oryxosRoot).toAbsolutePath().normalize();
    this.lifecycle = lifecycle;
  }

  /** 目录树：agents/（每个 Agent 一个可展开目录）+ archive/。 */
  @GetMapping("/tree")
  public ApiResponse<FileNode> tree() {
    List<FileNode> roots = new ArrayList<>();
    roots.add(treeOf(oryxosRoot.resolve("agents")));
    roots.add(treeOf(oryxosRoot.resolve("archive")));
    return ApiResponse.ok(FileNode.dir(".oryxos", "", roots));
  }

  /** 读文件文本；防目录穿越：越界 → 400，不存在 → 404。 */
  @GetMapping("/file")
  public ApiResponse<String> file(@RequestParam String path) {
    Path target = oryxosRoot.resolve(path).normalize();
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("路径越界，拒绝访问: " + path); // → 400
    }
    if (!Files.isRegularFile(target)) {
      throw new ResourceNotFoundException("文件不存在: " + path); // → 404
    }
    try {
      return ApiResponse.ok(Files.readString(target));
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  /** 写文件文本；防目录穿越：越界 → 400。编辑 Agent 的 AGENT.md 走 update 即时生效，其余文件直接写盘。 */
  @PostMapping("/file")
  public ApiResponse<Void> writeFile(@RequestBody WriteFileRequest req) {
    String path = req == null ? null : req.path();
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path 为空"); // → 400
    }
    Path target = oryxosRoot.resolve(path).normalize();
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("路径越界，拒绝访问: " + path); // → 400
    }
    String content = req.content() == null ? "" : req.content();
    Path agentDir = agentDirOfAgentFile(target);
    if (agentDir != null) {
      // 改的是某个 Agent 的 AGENT.md：走 update（写 + 校验 + 重注册，schedules 变更先注销旧）——即时生效
      lifecycle.update(String.valueOf(agentDir.getFileName()), content);
      return ApiResponse.ok(null);
    }
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败: " + path, e);
    }
    return ApiResponse.ok(null);
  }

  /** target 若是 {@code agents/<name>/AGENT.md} 则返回其 Agent 目录，否则 null。 */
  private Path agentDirOfAgentFile(Path target) {
    if (!AGENT_FILE.equals(String.valueOf(target.getFileName()))) {
      return null;
    }
    Path agentDir = target.getParent();
    if (agentDir == null || agentDir.getParent() == null) {
      return null;
    }
    return oryxosRoot.resolve("agents").equals(agentDir.getParent()) ? agentDir : null;
  }

  private FileNode treeOf(Path node) {
    String rel = oryxosRoot.relativize(node).toString();
    String name = String.valueOf(node.getFileName());
    if (!Files.isDirectory(node)) {
      return FileNode.file(name, rel);
    }
    List<FileNode> children = new ArrayList<>();
    try (Stream<Path> entries = Files.list(node)) {
      entries
          .sorted(Comparator.comparing(p -> String.valueOf(p.getFileName())))
          .forEach(child -> children.add(treeOf(child)));
    } catch (IOException e) {
      // 目录读不出来不阻断整棵树，返回空子节点
      return FileNode.dir(name, rel, List.of());
    }
    return FileNode.dir(name, rel, children);
  }
}
