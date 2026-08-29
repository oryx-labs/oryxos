package io.oryxos.tool.builtin;

import io.oryxos.core.fs.AdminConfigFileGuard;
import io.oryxos.core.fs.WorkspaceMutationGuard;
import io.oryxos.core.memory.MemoryMdGuard;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置文件工具（read_file / write_file / list_dir / edit_file / grep / glob）。
 *
 * <p>硬规矩：每个方法第一件事过 {@code sandbox.enforce} 路径白名单——校验不过异常拦下，文件根本不碰。
 */
public class FileTools {

  /** grep / glob 单次返回上限，防超大目录撑爆上下文。 */
  private static final int MAX_MATCHES = 200;

  /** glob 递归前缀；Java PathMatcher 对根下单段路径需去掉后再匹配。 */
  private static final String GLOB_RECURSIVE_PREFIX = "**/";

  private final Sandbox sandbox;

  public FileTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  /** 写路径保留文件守卫（MEMORY / AdminConfig / Skill·Knowledge / AGENT.md）。 */
  private static void rejectReservedFileWrites(String path) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(path);
  }

  @Tool(name = "read_file", description = "读取指定路径的文本文件内容")
  public String readFile(@ToolParam(description = "要读取的文件路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path file = Path.of(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      // 读前复检：与 write_file 同款——防首次校验到 readString 间路径被换成外向软链
      sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  @Tool(name = "write_file", description = "把内容写入指定路径的文件（覆盖写）")
  public String writeFile(
      @ToolParam(description = "要写入的文件路径") String path,
      @ToolParam(description = "要写入的内容") String content) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(path);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Path file = Path.of(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      // 写前复检：与 download_file / grep 同款——防首次校验到 writeString 间路径被换成外向软链
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      rejectReservedFileWrites(path);
      Files.writeString(file, content);
      return "已写入: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败: " + path, e);
    }
  }

  @Tool(name = "list_dir", description = "列出指定目录下的文件和子目录名")
  public String listDir(@ToolParam(description = "要列出的目录路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path dir = Path.of(path);
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    try {
      // 列举前复检：与 write_file / read_file 同款——防首次校验到 Files.list 间路径被换成外向软链
      sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
      try (Stream<Path> entries = Files.list(dir)) {
        return entries
            .map(p -> String.valueOf(p.getFileName()))
            .sorted()
            .collect(Collectors.joining("\n"));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("列目录失败: " + path, e);
    }
  }

  @Tool(name = "edit_file", description = "把文件中一段唯一出现的旧文本替换为新文本（局部编辑，不整文件覆盖）")
  public String editFile(
      @ToolParam(description = "要编辑的文件路径") String path,
      @ToolParam(description = "要被替换的原文本（必须在文件中唯一出现）") String oldString,
      @ToolParam(description = "替换后的新文本") String newString) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(path);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path file = Path.of(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      // 读前复检：与 read_file 同款——防首次校验到 readString 间路径被换成外向软链读出白名单
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      String content = Files.readString(file);
      int first = content.indexOf(oldString);
      if (first < 0) {
        throw new IllegalArgumentException("原文本在文件中未找到: " + path);
      }
      if (content.indexOf(oldString, first + 1) >= 0) {
        // 多处匹配会改错地方——要求唯一，逼调用方给足上下文（Claude Code/Cursor 同款约束）
        throw new IllegalArgumentException("原文本在文件中出现多次，无法定位唯一编辑点: " + path);
      }
      // 写前复检：与 write_file 同款
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      rejectReservedFileWrites(path);
      Files.writeString(file, content.replace(oldString, newString));
      return "已编辑: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("编辑文件失败: " + path, e);
    }
  }

  @Tool(name = "grep", description = "在指定路径下按正则搜索文件内容，返回匹配的 文件:行号:内容")
  public String grep(
      @ToolParam(description = "要搜索的正则表达式") String pattern,
      @ToolParam(description = "搜索根路径（文件或目录）") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path root = Path.of(path);
    if (!Files.exists(root)) {
      throw new IllegalArgumentException("路径不存在: " + path);
    }
    Pattern regex = Pattern.compile(pattern);
    List<String> matches = new ArrayList<>();
    try {
      // Skill 绑定等「目录软链」：walk 默认不跟随，需先解析到真实目录；嵌套文件软链仍靠 NOFOLLOW 跳过
      Path walkRoot = resolveDirectorySymlink(root);
      try (Stream<Path> files = Files.walk(walkRoot)) {
        for (Path file : (Iterable<Path>) files.filter(FileTools::isRealRegularFile)::iterator) {
          if (matches.size() >= MAX_MATCHES) {
            matches.add("...（已达 " + MAX_MATCHES + " 条上限，结果截断）");
            break;
          }
          // 纵深防御：每个实际读取的文件再过一次文件白名单（防根校验到读取间符号链接被替换）
          sandbox.enforce(new SandboxAction(ActionType.FILE_READ, file.toString()));
          appendMatches(file, regex, matches);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("搜索失败: " + path, e);
    }
    return matches.isEmpty() ? "（无匹配）" : String.join("\n", matches);
  }

  @Tool(name = "glob", description = "在指定目录下按 glob 通配（如 **/*.java）查找文件路径")
  public String glob(
      @ToolParam(description = "glob 通配模式，如 **/*.yaml") String pattern,
      @ToolParam(description = "查找根目录") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path root = Path.of(path);
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    List<String> hits = new ArrayList<>();
    try {
      Path walkRoot = resolveDirectorySymlink(root);
      try (Stream<Path> files = Files.walk(walkRoot)) {
        files
            .filter(FileTools::isRealRegularFile)
            .filter(p -> globMatches(matcher, pattern, walkRoot.relativize(p)))
            .limit(MAX_MATCHES)
            // 每个命中路径再过一次文件白名单（与 grep 同款纵深防御）
            .forEach(
                p -> {
                  sandbox.enforce(new SandboxAction(ActionType.FILE_READ, p.toString()));
                  hits.add(p.toString());
                });
      }
    } catch (IOException e) {
      throw new UncheckedIOException("查找失败: " + path, e);
    }
    return hits.isEmpty() ? "（无匹配）" : String.join("\n", hits);
  }

  /** 若根是指向目录的软链（Skill 绑定），解析到真实目录再 walk；否则原样返回。 */
  private static Path resolveDirectorySymlink(Path root) throws IOException {
    if (Files.isSymbolicLink(root) && Files.isDirectory(root)) {
      return root.toRealPath();
    }
    return root;
  }

  /**
   * Java {@code PathMatcher} 对 {@code **}{@code /} 前缀不匹配 walk 根下的单段相对路径（如 {@code SKILL.md}），对 Skill
   * 根目录文件补一次去掉该前缀后的匹配。
   */
  private static boolean globMatches(PathMatcher matcher, String pattern, Path relative) {
    if (matcher.matches(relative)) {
      return true;
    }
    if (pattern.startsWith(GLOB_RECURSIVE_PREFIX) && relative.getNameCount() == 1) {
      PathMatcher rest =
          FileSystems.getDefault()
              .getPathMatcher("glob:" + pattern.substring(GLOB_RECURSIVE_PREFIX.length()));
      return rest.matches(relative);
    }
    return false;
  }

  /** 普通文件判定不跟随符号链接：链接项（无论指向内部还是外部）都不作为可读文件参与搜索。 */
  private static boolean isRealRegularFile(Path file) {
    return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
  }

  private void appendMatches(Path file, Pattern regex, List<String> matches) {
    try {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
        if (regex.matcher(lines.get(i)).find()) {
          matches.add(file + ":" + (i + 1) + ":" + lines.get(i));
        }
      }
    } catch (IOException | java.io.UncheckedIOException e) {
      // 二进制/非 UTF-8 文件读不出来，跳过而非中断整次搜索
    }
  }

  // —— 文件管理（31 节丰富默认工具库）：建目录 / 追加 / 删除 / 移动 / 复制，均过路径白名单 ——

  @Tool(name = "make_dir", description = "创建目录（含父目录，幂等）")
  public String makeDir(@ToolParam(description = "要创建的目录路径") String path) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectBindSlotCreate(path);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Files.createDirectories(Path.of(path));
      // 建目录后复检：与 write_file / download_file 同款——防首次校验到 createDirectories 间路径被换成外向软链
      // 或换成仍在 root 内的 MEMORY / AdminConfig / Skill·Knowledge / bind 槽
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      MemoryMdGuard.rejectMutation(path);
      AdminConfigFileGuard.rejectMutation(path);
      WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
      WorkspaceMutationGuard.rejectBindSlotCreate(path);
      return "已创建目录: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("创建目录失败: " + path, e);
    }
  }

  @Tool(name = "append_file", description = "把内容追加到文件末尾（文件不存在则创建）")
  public String appendFile(
      @ToolParam(description = "文件路径") String path,
      @ToolParam(description = "要追加的内容") String content) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(path);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Path file = Path.of(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      rejectReservedFileWrites(path);
      Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      return "已追加到: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("追加文件失败: " + path, e);
    }
  }

  @Tool(name = "delete_file", description = "删除一个文件（拒绝删除目录）")
  public String deleteFile(@ToolParam(description = "要删除的文件路径") String path) {
    MemoryMdGuard.rejectMutation(path);
    AdminConfigFileGuard.rejectMutation(path);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(path);
    WorkspaceMutationGuard.rejectBindLinkDetach(path);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path file = Path.of(path);
    // NOFOLLOW_LINKS：只拦真实目录；指向目录的 symlink（如 Agent Skill 绑定）应删链接本身，不跟随目标
    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("拒绝删除目录（本工具只删文件）: " + path);
    }
    try {
      // 删前复检：防首次校验到 delete 间路径/父目录被换成外向软链，或换成仍在 root 内的保留路径
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
      rejectReservedFileWrites(path);
      WorkspaceMutationGuard.rejectBindLinkDetach(path);
      return Files.deleteIfExists(file) ? "已删除: " + path : "文件不存在: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("删除文件失败: " + path, e);
    }
  }

  @Tool(name = "move_file", description = "移动 / 重命名文件（源与目标都过白名单，目标已存在则覆盖）")
  public String moveFile(
      @ToolParam(description = "源路径") String from, @ToolParam(description = "目标路径") String to) {
    MemoryMdGuard.rejectMutation(from);
    AdminConfigFileGuard.rejectMutation(from);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(from);
    WorkspaceMutationGuard.rejectBindLinkDetach(from);
    MemoryMdGuard.rejectMutation(to);
    AdminConfigFileGuard.rejectMutation(to);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(to);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(to);
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, from));
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, to));
    Path src = Path.of(from);
    // NOFOLLOW：只拒真实目录；指向目录的 symlink（如 Skill 绑定）可移动链接本身
    if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("拒绝移动目录（本工具只移动文件）: " + from);
    }
    if (!Files.exists(src, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("源不存在: " + from);
    }
    try {
      Path dst = Path.of(to);
      // 跟随链接：真实目录与 symlink→dir（Skill 绑定）都拒——REPLACE_EXISTING 会删链接假成功
      rejectDirectoryDestination(dst, to);
      Path parent = dst.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      // 变更前复检：与 write_file 同款——防 createDirectories 窗口内目标父路径被换成外向软链
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, from));
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, to));
      rejectReservedFileWrites(from);
      rejectReservedFileWrites(to);
      Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
      return "已移动: " + from + " -> " + to;
    } catch (IOException e) {
      throw new UncheckedIOException("移动文件失败: " + from, e);
    }
  }

  @Tool(name = "copy_file", description = "复制文件（源读 + 目标写，都过白名单，目标已存在则覆盖）")
  public String copyFile(
      @ToolParam(description = "源路径") String from, @ToolParam(description = "目标路径") String to) {
    MemoryMdGuard.rejectMutation(to);
    AdminConfigFileGuard.rejectMutation(to);
    WorkspaceMutationGuard.rejectSkillKnowledgeContentWrite(to);
    WorkspaceMutationGuard.rejectAgentMdDirectWrite(to);
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, from));
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, to));
    Path src = Path.of(from);
    // 真实目录：拒绝（Files.copy 对目录会“成功”但只建空目录，造成假成功）
    if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("拒绝复制目录（本工具只复制文件）: " + from);
    }
    // 跟随后须为普通文件：symlink→file 可复制内容；symlink→dir / 缺失 → 拒绝
    if (!Files.isRegularFile(src)) {
      throw new IllegalArgumentException("源不存在或不是普通文件: " + from);
    }
    try {
      Path dst = Path.of(to);
      // 跟随链接：真实目录与 symlink→dir（Skill 绑定）都拒——REPLACE_EXISTING 会删链接假成功
      rejectDirectoryDestination(dst, to);
      Path parent = dst.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      // 变更前复检：与 write_file 同款——防校验到 copy 间路径被换成外向软链
      sandbox.enforce(new SandboxAction(ActionType.FILE_READ, from));
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, to));
      rejectReservedFileWrites(to);
      Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
      return "已复制: " + from + " -> " + to;
    } catch (IOException e) {
      throw new UncheckedIOException("复制文件失败: " + from, e);
    }
  }

  /** 目标若为目录（含跟随后的目录软链），拒绝覆盖——否则 REPLACE_EXISTING 会毁掉 Skill 绑定链接并假成功。 */
  private static void rejectDirectoryDestination(Path dst, String to) {
    if (Files.isDirectory(dst)) {
      throw new IllegalArgumentException("拒绝覆盖目录目标（本工具只写文件）: " + to);
    }
  }
}
