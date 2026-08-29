package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.fs.AdminConfigFileGuard;
import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.fs.WorkspaceMutationGuard;
import io.oryxos.core.memory.MemoryMdGuard;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.FileNode;
import io.oryxos.web.controller.dto.WriteFileRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作区文件浏览器（第 30 节）：列 {@code .oryxos/agents/} 与 {@code .oryxos/archive/} 的目录树、读写文件文本。
 *
 * <p>文件入口使用词法路径与真实路径双重边界校验：存在目标解析自身真实路径，不存在目标从最近存在父节点投影，软连接逃逸、悬空链接与链接环均 fail closed。编辑到某个 Agent 的
 * {@code AGENT.md} 时走 {@link AgentLifecycleService#update} 写入并即时重注册（macOS WatchService
 * 不监听子目录内文件变更，必须显式重注册）；其余文件直接写盘。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "PATH_TRAVERSAL_IN", "PATH_TRAVERSAL_OUT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway). path 通过 RealPathBoundary 做词法与真实路径边界校验，越界返回 400。lifecycle 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceApiController {

  private static final String AGENT_FILE = "AGENT.md";
  private static final String AGENTS_DIR = "agents";
  private static final String SKILLS_DIR = "skills";
  private static final String KNOWLEDGE_DIR = "knowledge";
  private static final String PARENT_PATH_SEGMENT = "..";

  /** 相对 {@code agents/}：{@code <name>/AGENT.md} 与 {@code <name>/skills/...} 的最小段数。 */
  private static final int AGENT_CHILD_SEGMENTS = 2;

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
    roots.add(
        treeOf(
            oryxosRoot.resolve(
                "skills"))); // 全局 Skill 库：每个 Skill 一个目录（SKILL.md + 可选脚本/子文档），供详情查看文件列表
    roots.add(treeOf(oryxosRoot.resolve("output"))); // 第 32 节：Agent 产出的共享目录（研报/汇总/导出）
    roots.add(treeOf(oryxosRoot.resolve("archive")));
    // 根节点显示名取实际工作区目录名（自定义 oryxos.root 时不再写死 .oryxos）
    Path rootName = oryxosRoot.getFileName();
    return ApiResponse.ok(
        FileNode.dir(rootName != null ? rootName.toString() : oryxosRoot.toString(), "", roots));
  }

  /** 读文件文本；防目录穿越：越界 → 400，不存在 → 404。 */
  @GetMapping("/file")
  public ApiResponse<String> file(@RequestParam String path) {
    Path target = resolveWithinRoot(path);
    if (!Files.isRegularFile(target)) {
      throw new ResourceNotFoundException("文件不存在: " + path); // → 404
    }
    try {
      // 读前复检：与 writeFile / tool 层 read_file 同款——防首次校验到 readString 间被换成外向软链
      target = resolveWithinRoot(path);
      return ApiResponse.ok(Files.readString(target));
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  /**
   * 下载文件（二进制附件流）：把 Agent {@code output/} 里的研报 / 汇总 / 导出等产出下载到本地。防目录穿越同 {@link #file}：越界 → 400，不存在 →
   * 404。区别于 {@link #file}（只返回文本、用于查看/编辑），这里带 {@code Content-Disposition:
   * attachment}、按内容类型返回原始字节，任意文件类型都能下。
   */
  @GetMapping("/download")
  public ResponseEntity<Resource> download(@RequestParam String path) {
    Path target = resolveWithinRoot(path);
    if (!Files.isRegularFile(target)) {
      throw new ResourceNotFoundException("文件不存在: " + path); // → 404
    }
    // 读前复检：与 file / writeFile 同款——防首次校验到打开附件间被换成外向软链
    target = resolveWithinRoot(path);
    String filename = String.valueOf(target.getFileName());
    // 文件名可能含中文/空格：用 RFC 5987 编码进 Content-Disposition，避免乱码或截断
    String disposition =
        ContentDisposition.attachment()
            .filename(filename, StandardCharsets.UTF_8)
            .build()
            .toString();
    MediaType contentType;
    long length;
    try {
      String probed = Files.probeContentType(target);
      contentType =
          probed != null ? MediaType.parseMediaType(probed) : MediaType.APPLICATION_OCTET_STREAM;
      length = Files.size(target);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
        .contentType(contentType)
        .contentLength(length)
        .body(new FileSystemResource(target));
  }

  /** 写文件文本；防目录穿越：越界 → 400。编辑 Agent 的 AGENT.md 走 update 即时生效，其余文件直接写盘。 */
  @PostMapping("/file")
  public ApiResponse<Void> writeFile(@RequestBody WriteFileRequest req) {
    String path = req == null ? null : req.path();
    if (path == null || path.isBlank()) {
      throw new IllegalArgumentException("path 为空"); // → 400
    }
    // 先词法拦直写；再投影真实路径后复检——notes.md→MEMORY.md / alias→channels.yaml 软链只在投影后能看见
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    Path target = resolveWithinRoot(path);
    MemoryMdGuard.rejectMutation(target);
    AdminConfigFileGuard.rejectMutation(target);
    if (isAgentSkillsPath(target)) {
      throw new IllegalArgumentException("Agent skills/ 是绑定视图，禁止从工作区入口写入");
    }
    if (isAgentKnowledgePath(target)) {
      throw new IllegalArgumentException("Agent knowledge/ 是绑定视图，禁止从工作区入口写入");
    }
    if (RealPathBoundary.isWithin(oryxosRoot.resolve(SKILLS_DIR), target)) {
      throw new IllegalArgumentException("共享 Skill 实体只能通过 Skill 管理入口更新");
    }
    if (RealPathBoundary.isWithin(oryxosRoot.resolve(KNOWLEDGE_DIR), target)) {
      throw new IllegalArgumentException("共享 Knowledge 实体只能通过 Knowledge 管理入口更新");
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
      // 写前复检：与 write_file / download_file 同款——防首次校验到 writeString 间父路径被换成外向软链
      target = resolveWithinRoot(path);
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败: " + path, e);
    }
    return ApiResponse.ok(null);
  }

  /**
   * target 若是 {@code agents/<name>/AGENT.md}（大小写不敏感）则返回其 Agent 目录，否则 null。
   *
   * <p>macOS/Windows 默认大小写不敏感：{@code agent.md} / {@code Agents/...} 仍指向同一文件，但 {@code Path.equals} /
   * {@code String.equals} 区分大小写，会绕过 {@link AgentLifecycleService#update} 直接写盘，schedules 变更不注销旧
   * cron。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "AGENT.md is an ASCII reserved filename; equalsIgnoreCase matches case-insensitive filesystems.")
  private Path agentDirOfAgentFile(Path target) {
    if (!AGENT_FILE.equalsIgnoreCase(String.valueOf(target.getFileName()))) {
      return null;
    }
    Path relative = relativeUnderAgents(target);
    if (relative == null || relative.getNameCount() != AGENT_CHILD_SEGMENTS) {
      return null;
    }
    return target.getParent();
  }

  /** {@code agents/<name>/skills/**}（大小写不敏感），含尚未落盘的 {@code Skills/} 后缀。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "skills is an ASCII reserved path segment; equalsIgnoreCase matches case-insensitive filesystems.")
  private boolean isAgentSkillsPath(Path target) {
    Path relative = relativeUnderAgents(target);
    return relative != null
        && relative.getNameCount() >= AGENT_CHILD_SEGMENTS
        && SKILLS_DIR.equalsIgnoreCase(relative.getName(1).toString());
  }

  /** {@code agents/<name>/knowledge/**}（大小写不敏感），与 skills 绑定视图同款禁写。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "knowledge is an ASCII reserved path segment; equalsIgnoreCase matches case-insensitive filesystems.")
  private boolean isAgentKnowledgePath(Path target) {
    Path relative = relativeUnderAgents(target);
    return relative != null
        && relative.getNameCount() >= AGENT_CHILD_SEGMENTS
        && KNOWLEDGE_DIR.equalsIgnoreCase(relative.getName(1).toString());
  }

  /** 相对真实 {@code agents/} 的路径；越出该目录或含 {@code ..} 则 null。 */
  private Path relativeUnderAgents(Path target) {
    Path agentsReal = RealPathBoundary.project(oryxosRoot.resolve(AGENTS_DIR)).projectedReal();
    Path relative;
    try {
      relative = agentsReal.relativize(target).normalize();
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (relative.getNameCount() == 0 || relative.isAbsolute()) {
      return null;
    }
    for (int i = 0; i < relative.getNameCount(); i++) {
      if (PARENT_PATH_SEGMENT.equals(relative.getName(i).toString())) {
        return null;
      }
    }
    return relative;
  }

  private FileNode treeOf(Path node) {
    String rel = oryxosRoot.relativize(node).toString();
    String name = String.valueOf(node.getFileName());
    if (Files.isSymbolicLink(node)) {
      String target;
      try {
        target = Files.readSymbolicLink(node).toString();
      } catch (IOException e) {
        target = "";
      }
      String status;
      if (!Files.exists(node)) {
        status = "dangling";
      } else {
        status = RealPathBoundary.isWithin(oryxosRoot, node) ? "valid" : "escaped";
      }
      return FileNode.link(name, rel, target, status);
    }
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

  private Path resolveWithinRoot(String path) {
    Path target = oryxosRoot.resolve(path).toAbsolutePath().normalize();
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("路径越界，拒绝访问: " + path);
    }
    try {
      // 用投影真实路径做后续分类：大小写不敏感盘上 Agents/agent.md 会回到 agents/AGENT.md。
      return RealPathBoundary.requireWithin(oryxosRoot, target);
    } catch (UncheckedIOException e) {
      throw new IllegalArgumentException("路径真实目标无法安全解析，拒绝访问: " + path, e);
    }
  }
}
