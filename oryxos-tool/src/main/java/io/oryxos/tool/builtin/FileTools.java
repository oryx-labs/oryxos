package io.oryxos.tool.builtin;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
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

  private final Sandbox sandbox;

  public FileTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Tool(name = "read_file", description = "读取指定路径的文本文件内容")
  public String readFile(@ToolParam(description = "要读取的文件路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path file = normalizedPath(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("读取文件失败: " + path, e);
    }
  }

  @Tool(name = "write_file", description = "把内容写入指定路径的文件（覆盖写）")
  public String writeFile(
      @ToolParam(description = "要写入的文件路径") String path,
      @ToolParam(description = "要写入的内容") String content) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Path file = normalizedPath(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, content);
      return "已写入: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("写入文件失败: " + path, e);
    }
  }

  @Tool(name = "list_dir", description = "列出指定目录下的文件和子目录名")
  public String listDir(@ToolParam(description = "要列出的目录路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, path));
    Path dir = normalizedPath(path);
    if (!Files.isDirectory(dir)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .map(p -> String.valueOf(p.getFileName()))
          .sorted()
          .collect(Collectors.joining("\n"));
    } catch (IOException e) {
      throw new UncheckedIOException("列目录失败: " + path, e);
    }
  }

  @Tool(name = "edit_file", description = "把文件中一段唯一出现的旧文本替换为新文本（局部编辑，不整文件覆盖）")
  public String editFile(
      @ToolParam(description = "要编辑的文件路径") String path,
      @ToolParam(description = "要被替换的原文本（必须在文件中唯一出现）") String oldString,
      @ToolParam(description = "替换后的新文本") String newString) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path file = normalizedPath(path);
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("文件不存在或不是普通文件: " + path);
    }
    try {
      String content = Files.readString(file);
      int first = content.indexOf(oldString);
      if (first < 0) {
        throw new IllegalArgumentException("原文本在文件中未找到: " + path);
      }
      if (content.indexOf(oldString, first + 1) >= 0) {
        // 多处匹配会改错地方——要求唯一，逼调用方给足上下文（Claude Code/Cursor 同款约束）
        throw new IllegalArgumentException("原文本在文件中出现多次，无法定位唯一编辑点: " + path);
      }
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
    Path root = normalizedPath(path);
    if (!Files.exists(root)) {
      throw new IllegalArgumentException("路径不存在: " + path);
    }
    Pattern regex = Pattern.compile(pattern);
    List<String> matches = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : (Iterable<Path>) files::iterator) {
        enforceReadablePath(file);
        if (!Files.isRegularFile(file)) {
          continue;
        }
        if (matches.size() >= MAX_MATCHES) {
          matches.add("...（已达 " + MAX_MATCHES + " 条上限，结果截断）");
          break;
        }
        appendMatches(file, regex, matches);
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
    Path root = normalizedPath(path);
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("目录不存在: " + path);
    }
    PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    List<String> hits = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      files
          .peek(this::enforceReadablePath)
          .filter(Files::isRegularFile)
          .filter(p -> matcher.matches(root.relativize(p)))
          .limit(MAX_MATCHES)
          .forEach(p -> hits.add(p.toString()));
    } catch (IOException e) {
      throw new UncheckedIOException("查找失败: " + path, e);
    }
    return hits.isEmpty() ? "（无匹配）" : String.join("\n", hits);
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
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Files.createDirectories(Path.of(path));
      return "已创建目录: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("创建目录失败: " + path, e);
    }
  }

  @Tool(name = "append_file", description = "把内容追加到文件末尾（文件不存在则创建）")
  public String appendFile(
      @ToolParam(description = "文件路径") String path,
      @ToolParam(description = "要追加的内容") String content) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    try {
      Path file = Path.of(path);
      Path parent = file.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      return "已追加到: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("追加文件失败: " + path, e);
    }
  }

  @Tool(name = "delete_file", description = "删除一个文件（拒绝删除目录）")
  public String deleteFile(@ToolParam(description = "要删除的文件路径") String path) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, path));
    Path file = Path.of(path);
    if (Files.isDirectory(file)) {
      throw new IllegalArgumentException("拒绝删除目录（本工具只删文件）: " + path);
    }
    try {
      return Files.deleteIfExists(file) ? "已删除: " + path : "文件不存在: " + path;
    } catch (IOException e) {
      throw new UncheckedIOException("删除文件失败: " + path, e);
    }
  }

  @Tool(name = "move_file", description = "移动 / 重命名文件（源与目标都过白名单，目标已存在则覆盖）")
  public String moveFile(
      @ToolParam(description = "源路径") String from, @ToolParam(description = "目标路径") String to) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, from));
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, to));
    try {
      Path dst = Path.of(to);
      Path parent = dst.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.move(Path.of(from), dst, StandardCopyOption.REPLACE_EXISTING);
      return "已移动: " + from + " -> " + to;
    } catch (IOException e) {
      throw new UncheckedIOException("移动文件失败: " + from, e);
    }
  }

  @Tool(name = "copy_file", description = "复制文件（源读 + 目标写，都过白名单，目标已存在则覆盖）")
  public String copyFile(
      @ToolParam(description = "源路径") String from, @ToolParam(description = "目标路径") String to) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, from));
    sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, to));
    try {
      Path dst = Path.of(to);
      Path parent = dst.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.copy(Path.of(from), dst, StandardCopyOption.REPLACE_EXISTING);
      return "已复制: " + from + " -> " + to;
    } catch (IOException e) {
      throw new UncheckedIOException("复制文件失败: " + from, e);
    }
  }

  private void enforceReadablePath(Path file) {
    sandbox.enforce(new SandboxAction(ActionType.FILE_READ, file.toString()));
  }

  private Path normalizedPath(String path) {
    return Path.of(path).toAbsolutePath().normalize();
  }
}
