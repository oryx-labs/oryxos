package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentName;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.SkillLease;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.FileNode;
import io.oryxos.web.controller.dto.WriteFileRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * <p>唯一安全要点——**防目录穿越**：{@code file}/{@code writeFile} 把 path 解析成绝对路径 {@code normalize()} 后必须 {@code
 * startsWith(oryxosRoot)}，越界即 400。编辑到某个 Agent 的 {@code AGENT.md} 时走 {@link
 * AgentLifecycleService#update} 写入并即时重注册（macOS WatchService 不监听子目录内文件变更，必须显式重注册）；其余文件直接写盘。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "PATH_TRAVERSAL_IN", "PATH_TRAVERSAL_OUT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway). path 已做"
            + " normalize()+startsWith(oryxosRoot) 白名单校验，越界 400——这正是防目录穿越的正解。lifecycle 是 Spring"
            + " 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceApiController {

  private static final String AGENT_FILE = "AGENT.md";
  private static final String AGENTS_DIRECTORY = "agents";
  private static final String ARCHIVE_DIRECTORY = "archive";
  private static final String SKILLS_DIRECTORY = "skills";
  private static final String OUTPUT_DIRECTORY = "output";
  private static final String MARKDOWN_SUFFIX = ".md";
  private static final String SKILL_DISABLED_MARKER = ".oryxos-disabled";
  private static final String SKILL_ORIGIN_FILE = ".oryxos-origin.yml";
  private static final char WINDOWS_PATH_SEPARATOR = '\\';
  private static final char NULL_CHARACTER = '\0';
  private static final int ROOT_SEGMENT_INDEX = 0;
  private static final int AGENT_SEGMENT_INDEX = 1;
  private static final int SKILLS_SEGMENT_INDEX = 2;
  private static final int SKILL_SEGMENT_INDEX = 3;
  private static final int MANAGED_SKILL_ROOT_SEGMENT_COUNT = 3;
  private static final int SKILL_DIRECTORY_SEGMENT_COUNT = 4;

  private final Path oryxosRoot;
  private final AgentLifecycleService lifecycle;
  private final AgentSkillCoordinator skillCoordinator;

  public WorkspaceApiController(
      @org.springframework.beans.factory.annotation.Value("${oryxos.root:.oryxos}")
          String oryxosRoot,
      AgentLifecycleService lifecycle) {
    this(oryxosRoot, lifecycle, null);
  }

  /** 生产构造器：受管 Skill 的读取/写入与运行请求共用同一个 Agent 级协调器。 */
  @org.springframework.beans.factory.annotation.Autowired
  public WorkspaceApiController(
      @org.springframework.beans.factory.annotation.Value("${oryxos.root:.oryxos}")
          String oryxosRoot,
      AgentLifecycleService lifecycle,
      AgentSkillCoordinator skillCoordinator) {
    this.oryxosRoot = Path.of(oryxosRoot).toAbsolutePath().normalize();
    this.lifecycle = lifecycle;
    this.skillCoordinator = skillCoordinator;
  }

  /** 目录树：agents/（每个 Agent 一个可展开目录）+ archive/。 */
  @GetMapping("/tree")
  public ApiResponse<FileNode> tree() {
    List<FileNode> roots = new ArrayList<>();
    roots.add(treeOf(oryxosRoot.resolve(AGENTS_DIRECTORY)));
    roots.add(treeOf(oryxosRoot.resolve(SKILLS_DIRECTORY)));
    roots.add(treeOf(oryxosRoot.resolve(OUTPUT_DIRECTORY)));
    roots.add(treeOf(oryxosRoot.resolve(ARCHIVE_DIRECTORY)));
    // 根节点显示名取实际工作区目录名（自定义 oryxos.root 时不再写死 .oryxos）
    Path rootName = oryxosRoot.getFileName();
    return ApiResponse.ok(
        FileNode.dir(rootName != null ? rootName.toString() : oryxosRoot.toString(), "", roots));
  }

  /** 读文件文本；防目录穿越：越界 → 400，不存在 → 404。 */
  @GetMapping("/file")
  public ApiResponse<String> file(@RequestParam("path") String path) {
    Path target = resolveWorkspacePath(path);
    ManagedSkillPath managed = managedSkillPath(target);
    if (managed != null && skillCoordinator != null) {
      try (SkillLease ignored = skillCoordinator.openRequest(managed.agentName())) {
        return ApiResponse.ok(readSafeFile(target, path));
      }
    }
    return ApiResponse.ok(readSafeFile(target, path));
  }

  /**
   * 下载文件（二进制附件流）：把 Agent {@code output/} 里的研报 / 汇总 / 导出等产出下载到本地。防目录穿越同 {@link #file}：越界 → 400，不存在 →
   * 404。区别于 {@link #file}（只返回文本、用于查看/编辑），这里带 {@code Content-Disposition:
   * attachment}、按内容类型返回原始字节，任意文件类型都能下。
   */
  @GetMapping("/download")
  public ResponseEntity<Resource> download(@RequestParam String path) {
    Path target = oryxosRoot.resolve(path).normalize();
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("路径越界，拒绝访问: " + path); // → 400
    }
    if (!Files.isRegularFile(target)) {
      throw new ResourceNotFoundException("文件不存在: " + path); // → 404
    }
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
    Path target = resolveWorkspacePath(path);
    rejectArchiveWrite(target);
    String content = req.content() == null ? "" : req.content();
    Path agentDir = agentDirOfAgentFile(target);
    if (agentDir != null) {
      // 改的是某个 Agent 的 AGENT.md：走 update（写 + 校验 + 重注册，schedules 变更先注销旧）——即时生效
      lifecycle.update(String.valueOf(agentDir.getFileName()), content);
      return ApiResponse.ok(null);
    }
    ManagedSkillPath managed = managedSkillPath(target);
    if (managed != null) {
      rejectReservedSkillFile(target);
      ensureSafeWriteParent(target);
      lifecycle.writeManagedSkillFile(managed.agentName(), managed.relativeWithinAgent(), content);
      return ApiResponse.ok(null);
    }
    try {
      Path parent = target.getParent();
      if (parent != null) {
        ensureSafeWriteParent(target);
        Files.createDirectories(parent);
        ensureSafeWriteParent(target);
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
    return oryxosRoot.resolve(AGENTS_DIRECTORY).equals(agentDir.getParent()) ? agentDir : null;
  }

  private Path resolveWorkspacePath(String path) {
    if (path == null
        || path.isBlank()
        || path.indexOf(WINDOWS_PATH_SEPARATOR) >= 0
        || path.indexOf(NULL_CHARACTER) >= 0) {
      throw new IllegalArgumentException("非法工作区路径");
    }
    Path relative = Path.of(path);
    if (relative.isAbsolute()) {
      throw new IllegalArgumentException("路径越界，拒绝访问: " + path);
    }
    for (Path segment : relative) {
      if (".".equals(segment.toString()) || "..".equals(segment.toString())) {
        throw new IllegalArgumentException("路径越界，拒绝访问: " + path);
      }
    }
    rejectAmbiguousReservedCasing(relative);
    Path target = oryxosRoot.resolve(relative).normalize();
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("路径越界，拒绝访问: " + path);
    }
    return target;
  }

  /**
   * 识别严格的 {@code agents/<agent>/skills/<skill>/<file...>}。旧版 {@code agents/<agent>/skills/*.md}
   * 与其他普通目录仍走 generic 路径。
   */
  private ManagedSkillPath managedSkillPath(Path target) {
    Path relative = oryxosRoot.relativize(target);
    if (relative.getNameCount() < MANAGED_SKILL_ROOT_SEGMENT_COUNT
        || !AGENTS_DIRECTORY.equals(relative.getName(ROOT_SEGMENT_INDEX).toString())
        || !SKILLS_DIRECTORY.equals(relative.getName(SKILLS_SEGMENT_INDEX).toString())) {
      return null;
    }
    AgentName agent = AgentName.parse(relative.getName(AGENT_SEGMENT_INDEX).toString());
    if (relative.getNameCount() == MANAGED_SKILL_ROOT_SEGMENT_COUNT) {
      throw new IllegalArgumentException("Skill 根目录不能通过 Workspace 文件接口修改");
    }
    if (relative.getNameCount() == SKILL_DIRECTORY_SEGMENT_COUNT) {
      String legacyFile = relative.getName(SKILL_SEGMENT_INDEX).toString();
      if (legacyFile.endsWith(MARKDOWN_SUFFIX)) {
        return null;
      }
      throw new IllegalArgumentException("Skill 包目录不能通过 Workspace 文件接口修改");
    }
    String skillDirectory = relative.getName(SKILL_SEGMENT_INDEX).toString();
    if (skillDirectory.isBlank()
        || ".".equals(skillDirectory)
        || "..".equals(skillDirectory)
        || skillDirectory.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("非法 Skill 目录名");
    }
    return new ManagedSkillPath(
        agent.value(), relative.subpath(SKILLS_SEGMENT_INDEX, relative.getNameCount()).toString());
  }

  private String readSafeFile(Path target, String requestedPath) {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ResourceNotFoundException("文件不存在: " + requestedPath);
    }
    ensureNoSymbolicLinks(target);
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new ResourceNotFoundException("文件不存在: " + requestedPath);
    }
    try {
      Path rootReal = oryxosRoot.toRealPath();
      Path targetReal = target.toRealPath();
      if (!targetReal.startsWith(rootReal)) {
        throw new IllegalArgumentException("文件路径越出工作区");
      }
      return Files.readString(target);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + requestedPath, e);
    }
  }

  private void ensureSafeWriteParent(Path target) {
    ensureNoSymbolicLinks(target);
    Path existing = target.getParent();
    while (existing != null
        && existing.startsWith(oryxosRoot)
        && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      existing = existing.getParent();
    }
    if (existing == null || !existing.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("目标父级越出工作区");
    }
    try {
      Path rootReal = oryxosRoot.toRealPath();
      if (!existing.toRealPath().startsWith(rootReal)) {
        throw new IllegalArgumentException("目标父级越出工作区");
      }
    } catch (IOException e) {
      throw new UncheckedIOException("检查目标父级失败", e);
    }
  }

  private void ensureNoSymbolicLinks(Path target) {
    if (!target.startsWith(oryxosRoot)) {
      throw new IllegalArgumentException("文件路径越出工作区");
    }
    Path current = oryxosRoot;
    if (Files.isSymbolicLink(current)) {
      throw new IllegalArgumentException("工作区路径包含符号链接");
    }
    for (Path segment : oryxosRoot.relativize(target)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IllegalArgumentException("文件路径包含符号链接: " + segment);
      }
    }
  }

  private static void rejectReservedSkillFile(Path target) {
    String name = String.valueOf(target.getFileName());
    if (matchesReservedAsciiName(name, SKILL_DISABLED_MARKER)
        || matchesReservedAsciiName(name, SKILL_ORIGIN_FILE)) {
      throw new IllegalArgumentException("保留 Skill 状态文件不能通过 Workspace API 修改");
    }
  }

  private void rejectArchiveWrite(Path target) {
    Path relative = oryxosRoot.relativize(target);
    if (relative.getNameCount() > 0
        && matchesReservedAsciiName(
            relative.getName(ROOT_SEGMENT_INDEX).toString(), ARCHIVE_DIRECTORY)) {
      throw new IllegalArgumentException("归档目录只读，不能通过 Workspace API 修改");
    }
  }

  /**
   * Reserved workspace namespaces must use canonical ASCII casing. Case-insensitive file systems
   * otherwise let lexical variants alias the same file while bypassing the managed routing checks.
   */
  private static void rejectAmbiguousReservedCasing(Path relative) {
    if (relative.getNameCount() == 0) {
      return;
    }
    String root = relative.getName(ROOT_SEGMENT_INDEX).toString();
    if (isNonCanonicalCaseAlias(root, AGENTS_DIRECTORY)) {
      throw new IllegalArgumentException("保留工作区路径必须使用规范大小写");
    }
    if (isNonCanonicalCaseAlias(root, ARCHIVE_DIRECTORY)) {
      throw new IllegalArgumentException("保留工作区路径必须使用规范大小写");
    }
    if (!AGENTS_DIRECTORY.equals(root)) {
      return;
    }
    if (relative.getNameCount() > SKILLS_SEGMENT_INDEX) {
      String skills = relative.getName(SKILLS_SEGMENT_INDEX).toString();
      if (isNonCanonicalCaseAlias(skills, SKILLS_DIRECTORY)) {
        throw new IllegalArgumentException("Skill 路径必须使用规范大小写");
      }
    }
    if (relative.getNameCount() == MANAGED_SKILL_ROOT_SEGMENT_COUNT) {
      String agentFile = relative.getName(SKILLS_SEGMENT_INDEX).toString();
      if (isNonCanonicalCaseAlias(agentFile, AGENT_FILE)) {
        throw new IllegalArgumentException("AGENT.md 路径必须使用规范大小写");
      }
    }
  }

  private static boolean isNonCanonicalCaseAlias(String value, String canonicalValue) {
    if (canonicalValue.equals(value)) {
      return false;
    }
    return matchesReservedAsciiName(value, canonicalValue);
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "Both canonical names are fixed ASCII workspace tokens. The comparison only rejects"
              + " aliases; it never grants access or derives a persisted identity.")
  private static boolean matchesReservedAsciiName(String value, String canonicalValue) {
    return canonicalValue.equalsIgnoreCase(value);
  }

  private FileNode treeOf(Path node) {
    String rel = oryxosRoot.relativize(node).toString();
    String name = String.valueOf(node.getFileName());
    if (!Files.isDirectory(node, LinkOption.NOFOLLOW_LINKS)) {
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

  private record ManagedSkillPath(String agentName, String relativeWithinAgent) {}
}
