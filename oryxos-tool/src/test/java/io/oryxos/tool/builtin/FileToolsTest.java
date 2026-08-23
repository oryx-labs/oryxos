package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.FileSandboxProperties;
import io.oryxos.tool.sandbox.HttpSandboxProperties;
import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import io.oryxos.tool.sandbox.ShellSandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第20节》验收 harness：FileToolsTest——正常能跑通 + 越界会被拦。 */
class FileToolsTest {

  @TempDir Path dir;

  private final FileTools tools = new FileTools(new PermissiveSandbox());

  @Test
  @DisplayName("make_dir + append_file + delete_file 基本闭环")
  void fileManagementBasics() throws IOException {
    tools.makeDir(dir.resolve("sub").toString());
    assertTrue(Files.isDirectory(dir.resolve("sub")));
    String f = dir.resolve("sub/log.txt").toString();
    tools.appendFile(f, "line1\n");
    tools.appendFile(f, "line2\n");
    assertEquals("line1\nline2\n", Files.readString(Path.of(f)));
    tools.deleteFile(f);
    assertFalse(Files.exists(Path.of(f)));
  }

  @Test
  @DisplayName("copy_file 复制、move_file 移动后源不在目标在")
  void copyThenMove() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "x");
    tools.copyFile(dir.resolve("a.txt").toString(), dir.resolve("b.txt").toString());
    assertEquals("x", Files.readString(dir.resolve("b.txt")));
    tools.moveFile(dir.resolve("b.txt").toString(), dir.resolve("c.txt").toString());
    assertFalse(Files.exists(dir.resolve("b.txt")));
    assertEquals("x", Files.readString(dir.resolve("c.txt")));
  }

  @Test
  @DisplayName("copy_file / move_file 拒绝真实目录（避免假成功空目录）")
  void copyAndMoveRejectRealDirectories() throws IOException {
    Path srcDir = dir.resolve("srcdir");
    Files.createDirectories(srcDir);
    Files.writeString(srcDir.resolve("keep.txt"), "data");
    Path copyDest = dir.resolve("copied-dir");
    Path moveDest = dir.resolve("moved-dir");

    assertThrows(
        IllegalArgumentException.class,
        () -> tools.copyFile(srcDir.toString(), copyDest.toString()));
    assertFalse(Files.exists(copyDest), "拒绝对目录复制后不得留下空目录");
    assertTrue(Files.exists(srcDir.resolve("keep.txt")));

    assertThrows(
        IllegalArgumentException.class,
        () -> tools.moveFile(srcDir.toString(), moveDest.toString()));
    assertTrue(Files.isDirectory(srcDir), "真实目录不得被挪走");
    assertFalse(Files.exists(moveDest));
  }

  @Test
  @DisplayName("copy_file 拒绝指向目录的 symlink（不建空目录）")
  void copyRejectsSymlinkToDirectory() throws IOException {
    Path targetDir = dir.resolve("skill-body");
    Files.createDirectories(targetDir);
    Files.writeString(targetDir.resolve("SKILL.md"), "keep");
    Path link = dir.resolve("skill-link");
    try {
      Files.createSymbolicLink(link, targetDir);
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.assumeTrue(false, "本机无法创建符号链接，跳过: " + e.getMessage());
    }
    Path dest = dir.resolve("out-copy");

    assertThrows(
        IllegalArgumentException.class, () -> tools.copyFile(link.toString(), dest.toString()));
    assertFalse(Files.exists(dest), "不得留下假成功的空目录");
    assertTrue(Files.exists(targetDir.resolve("SKILL.md")));
  }

  @Test
  @DisplayName("copy_file / move_file 拒绝覆盖目录目标（含 symlink→dir Skill 绑定）")
  void copyAndMoveRejectDirectoryDestination() throws IOException {
    Files.writeString(dir.resolve("payload.txt"), "x");
    Path destDir = dir.resolve("dest-dir");
    Files.createDirectories(destDir);
    Files.writeString(destDir.resolve("keep.txt"), "stay");

    assertThrows(
        IllegalArgumentException.class,
        () -> tools.copyFile(dir.resolve("payload.txt").toString(), destDir.toString()));
    assertTrue(Files.isDirectory(destDir));
    assertEquals("stay", Files.readString(destDir.resolve("keep.txt")));

    Path skillBody = dir.resolve("skill-body-dst");
    Files.createDirectories(skillBody);
    Files.writeString(skillBody.resolve("SKILL.md"), "bound");
    Path skillLink = dir.resolve("agents/ops/skills/report");
    Files.createDirectories(skillLink.getParent());
    try {
      Files.createSymbolicLink(skillLink, skillBody);
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.assumeTrue(false, "本机无法创建符号链接，跳过: " + e.getMessage());
    }

    assertThrows(
        IllegalArgumentException.class,
        () -> tools.copyFile(dir.resolve("payload.txt").toString(), skillLink.toString()));
    assertTrue(Files.isSymbolicLink(skillLink), "Skill 绑定链接不得被 REPLACE 毁掉");
    assertEquals("bound", Files.readString(skillBody.resolve("SKILL.md")));

    assertThrows(
        IllegalArgumentException.class,
        () -> tools.moveFile(dir.resolve("payload.txt").toString(), skillLink.toString()));
    assertTrue(Files.isSymbolicLink(skillLink));
    assertTrue(Files.exists(dir.resolve("payload.txt")), "拒绝后源文件应仍在");
  }

  @Test
  @DisplayName("delete_file 拒绝删除目录")
  void deleteRejectsDirectory() {
    assertThrows(IllegalArgumentException.class, () -> tools.deleteFile(dir.toString()));
  }

  @Test
  @DisplayName("delete_file 可删指向目录的 symlink（不跟随目标）")
  void deleteRemovesSymlinkToDirectoryWithoutRemovingTarget() throws IOException {
    Path targetDir = dir.resolve("skill-body");
    Files.createDirectories(targetDir);
    Files.writeString(targetDir.resolve("SKILL.md"), "keep");
    Path link = dir.resolve("skill-link");
    try {
      Files.createSymbolicLink(link, targetDir);
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.assumeTrue(false, "本机无法创建符号链接，跳过: " + e.getMessage());
    }

    String result = tools.deleteFile(link.toString());

    assertTrue(result.contains("已删除"));
    assertFalse(Files.exists(link), "应删除链接本身");
    assertTrue(Files.isDirectory(targetDir), "目标目录不得被删");
    assertTrue(Files.exists(targetDir.resolve("SKILL.md")));
  }

  @Test
  @DisplayName("read_file 正常读到内容")
  void readFileReturnsContent() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "hello oryx");

    assertEquals("hello oryx", tools.readFile(dir.resolve("a.txt").toString()));
  }

  @Test
  @DisplayName("write_file 写入成功且可回读")
  void writeFilePersistsContent() throws IOException {
    tools.writeFile(dir.resolve("out/b.txt").toString(), "written");

    assertEquals("written", Files.readString(dir.resolve("out/b.txt")));
  }

  @Test
  @DisplayName("write_file 落盘前复检 FILE_WRITE（防校验窗口内路径逃逸）")
  void writeFileRechecksPathBeforeWrite() {
    AtomicInteger fileWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE && fileWrites.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    Path target = dir.resolve("escape.txt");
    FileTools guarded = new FileTools(sandbox);

    assertThrows(
        SandboxViolationException.class, () -> guarded.writeFile(target.toString(), "secret"));
    assertEquals(2, fileWrites.get(), "应在写前后各 enforce 一次 FILE_WRITE");
    assertTrue(Files.notExists(target), "复检拒绝后不得落盘");
  }

  @Test
  @DisplayName("make_dir 建目录后复检 FILE_WRITE（防校验窗口内路径逃逸）")
  void makeDirRechecksPathAfterCreateDirectories() {
    AtomicInteger fileWrites = new AtomicInteger();
    Path nested = dir.resolve("nested");
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE) {
            int n = fileWrites.incrementAndGet();
            if (n >= 2) {
              // 复检必须在 createDirectories 之后：此时目标目录应已存在
              assertTrue(Files.isDirectory(nested), "复检应发生在 createDirectories 之后");
              throw new SandboxViolationException("复检拒绝: " + action.target());
            }
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(SandboxViolationException.class, () -> guarded.makeDir(nested.toString()));
    assertEquals(2, fileWrites.get(), "应在建目录前与 createDirectories 后各 enforce 一次 FILE_WRITE");
  }

  @Test
  @DisplayName("read_file 读取前复检 FILE_READ")
  void readFileRechecksPathBeforeRead() throws IOException {
    Path target = dir.resolve("secret.txt");
    Files.writeString(target, "classified");
    AtomicInteger fileReads = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_READ && fileReads.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(SandboxViolationException.class, () -> guarded.readFile(target.toString()));
    assertEquals(2, fileReads.get(), "应在读前后各 enforce 一次 FILE_READ");
    assertEquals("classified", Files.readString(target), "复检拒绝后文件仍在");
  }

  @Test
  @DisplayName("delete_file 删除前复检 FILE_WRITE")
  void deleteFileRechecksPathBeforeDelete() throws IOException {
    Path target = dir.resolve("keep.txt");
    Files.writeString(target, "keep-me");
    AtomicInteger fileWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE && fileWrites.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(SandboxViolationException.class, () -> guarded.deleteFile(target.toString()));
    assertEquals(2, fileWrites.get());
    assertTrue(Files.exists(target), "复检拒绝后不得删除");
    assertEquals("keep-me", Files.readString(target));
  }

  @Test
  @DisplayName("append_file 落盘前复检 FILE_WRITE")
  void appendFileRechecksPathBeforeWrite() {
    AtomicInteger fileWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE && fileWrites.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    Path target = dir.resolve("append-escape.txt");
    FileTools guarded = new FileTools(sandbox);

    assertThrows(SandboxViolationException.class, () -> guarded.appendFile(target.toString(), "x"));
    assertEquals(2, fileWrites.get());
    assertTrue(Files.notExists(target));
  }

  @Test
  @DisplayName("edit_file 读前复检 FILE_WRITE（防 readString 窗口读出白名单）")
  void editFileRechecksPathBeforeRead() throws IOException {
    Path target = dir.resolve("edit-read.txt");
    Files.writeString(target, "hello");
    AtomicInteger fileWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE && fileWrites.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(
        SandboxViolationException.class,
        () -> guarded.editFile(target.toString(), "hello", "world"));
    assertEquals(2, fileWrites.get(), "应在入口与 readString 前各 enforce 一次");
    assertEquals("hello", Files.readString(target), "读前复检拒绝后不得改写");
  }

  @Test
  @DisplayName("edit_file 写回前复检 FILE_WRITE")
  void editFileRechecksPathBeforeWrite() throws IOException {
    Path target = dir.resolve("edit.txt");
    Files.writeString(target, "hello");
    AtomicInteger fileWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE && fileWrites.incrementAndGet() >= 3) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(
        SandboxViolationException.class,
        () -> guarded.editFile(target.toString(), "hello", "world"));
    assertEquals(3, fileWrites.get(), "入口 / 读前 / 写前共三次 FILE_WRITE");
    assertEquals("hello", Files.readString(target), "复检拒绝后不得改写");
  }

  @Test
  @DisplayName("copy_file 落盘前复检 FILE_WRITE（防校验窗口内路径逃逸）")
  void copyFileRechecksPathBeforeCopy() throws IOException {
    Path src = dir.resolve("src.txt");
    Files.writeString(src, "payload");
    Path dst = dir.resolve("nested/out.txt");
    AtomicInteger destWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE
              && action.target().equals(dst.toString())
              && destWrites.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(
        SandboxViolationException.class, () -> guarded.copyFile(src.toString(), dst.toString()));
    assertEquals(2, destWrites.get(), "目标应在 copy 前后各 enforce 一次 FILE_WRITE");
    assertTrue(Files.notExists(dst), "复检拒绝后不得复制落盘");
    assertTrue(Files.exists(src), "源应保留");
  }

  @Test
  @DisplayName("move_file 落盘前复检 FILE_WRITE")
  void moveFileRechecksPathBeforeMove() throws IOException {
    Path src = dir.resolve("move-src.txt");
    Files.writeString(src, "payload");
    Path dst = dir.resolve("nested/moved.txt");
    AtomicInteger destWrites = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_WRITE
              && action.target().equals(dst.toString())
              && destWrites.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(
        SandboxViolationException.class, () -> guarded.moveFile(src.toString(), dst.toString()));
    assertEquals(2, destWrites.get());
    assertTrue(Files.notExists(dst), "复检拒绝后不得移动落盘");
    assertTrue(Files.exists(src), "源应保留");
  }

  @Test
  @DisplayName("list_dir 列出目录条目")
  void listDirShowsEntries() throws IOException {
    Files.writeString(dir.resolve("x.txt"), "");
    Files.createDirectory(dir.resolve("sub"));

    String listing = tools.listDir(dir.toString());

    assertTrue(listing.contains("x.txt"));
    assertTrue(listing.contains("sub"));
  }

  @Test
  @DisplayName("list_dir 列举前复检 FILE_READ")
  void listDirRechecksPathBeforeList() throws IOException {
    Files.writeString(dir.resolve("x.txt"), "");
    AtomicInteger fileReads = new AtomicInteger();
    Sandbox sandbox =
        action -> {
          if (action.type() == ActionType.FILE_READ && fileReads.incrementAndGet() >= 2) {
            throw new SandboxViolationException("复检拒绝: " + action.target());
          }
        };
    FileTools guarded = new FileTools(sandbox);

    assertThrows(SandboxViolationException.class, () -> guarded.listDir(dir.toString()));
    assertEquals(2, fileReads.get(), "应在 list 前后各 enforce 一次 FILE_READ");
  }

  @Test
  @DisplayName("读不存在的文件_报错点名路径")
  void readMissingFileFailsWithPath() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> tools.readFile(dir.resolve("no.txt").toString()));

    assertTrue(ex.getMessage().contains("no.txt"));
  }

  @Test
  @DisplayName("越界会被拦：白名单拒绝时文件动作零发生")
  void sandboxRejectionBlocksAllFileActions() throws IOException {
    Sandbox denying = mock(Sandbox.class);
    doThrow(new SandboxViolationException("路径不在白名单")).when(denying).enforce(any());
    FileTools guarded = new FileTools(denying);
    Path target = dir.resolve("guarded.txt");

    assertThrows(SandboxViolationException.class, () -> guarded.readFile(target.toString()));
    assertThrows(SandboxViolationException.class, () -> guarded.writeFile(target.toString(), "x"));
    assertThrows(SandboxViolationException.class, () -> guarded.listDir(dir.toString()));
    assertThrows(
        SandboxViolationException.class, () -> guarded.makeDir(dir.resolve("new").toString()));
    assertThrows(SandboxViolationException.class, () -> guarded.appendFile(target.toString(), "x"));
    assertThrows(SandboxViolationException.class, () -> guarded.deleteFile(target.toString()));
    assertThrows(
        SandboxViolationException.class,
        () -> guarded.moveFile(target.toString(), dir.resolve("moved").toString()));
    assertThrows(
        SandboxViolationException.class,
        () -> guarded.copyFile(target.toString(), dir.resolve("copied").toString()));
    assertFalse(Files.exists(target), "校验不过，文件根本不该被创建");
    assertFalse(Files.exists(dir.resolve("new")));
    assertFalse(Files.exists(dir.resolve("moved")));
    assertFalse(Files.exists(dir.resolve("copied")));
  }

  @Test
  @DisplayName("白名单外文件_读取被拦_文件根本不碰")
  void readOutsideWhitelist_fileNeverTouched() {
    // 真 WhitelistSandbox（白名单只含 @TempDir），读白名单外且不存在的路径——
    // 若 enforce 未先拦，会因文件缺失抛 IllegalArgumentException；抛 SandboxViolationException 才证明校验先于 IO
    Sandbox whitelist =
        new WhitelistSandbox(
            new FileSandboxProperties(List.of(dir.toString())),
            new ShellSandboxProperties(List.of()),
            new HttpSandboxProperties(List.of()));
    FileTools guarded = new FileTools(whitelist);

    assertThrows(
        SandboxViolationException.class, () -> guarded.readFile("/etc/oryxos-nonexistent.secret"));
  }

  @Test
  @DisplayName("edit_file 唯一匹配时替换成功")
  void editFileReplacesUniqueMatch() throws IOException {
    Path file = dir.resolve("c.yaml");
    Files.writeString(file, "model: deepseek-chat\ntemp: 0.7\n");

    tools.editFile(file.toString(), "deepseek-chat", "deepseek-reasoner");

    assertEquals("model: deepseek-reasoner\ntemp: 0.7\n", Files.readString(file));
  }

  @Test
  @DisplayName("edit_file 原文本缺失或多处_报错不改文件")
  void editFileRejectsMissingOrAmbiguous() throws IOException {
    Path file = dir.resolve("d.txt");
    Files.writeString(file, "x\nx\n");

    assertThrows(
        IllegalArgumentException.class, () -> tools.editFile(file.toString(), "nope", "y"));
    assertThrows(
        IllegalArgumentException.class, () -> tools.editFile(file.toString(), "x", "y")); // 两处
    assertEquals("x\nx\n", Files.readString(file), "报错时文件不该被改动");
  }

  @Test
  @DisplayName("grep 返回 文件:行号:内容")
  void grepReturnsFileLineContent() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "alpha\nbeta needle\ngamma\n");
    Files.writeString(dir.resolve("b.txt"), "no match here\n");

    String result = tools.grep("needle", dir.toString());

    assertTrue(result.contains("a.txt:2:beta needle"));
    assertFalse(result.contains("b.txt"));
  }

  @Test
  @DisplayName("grep 无匹配返回明确提示")
  void grepNoMatchIsExplicit() throws IOException {
    Files.writeString(dir.resolve("a.txt"), "nothing\n");

    assertEquals("（无匹配）", tools.grep("zzz", dir.toString()));
  }

  @Test
  @DisplayName("glob 按通配找到文件路径")
  void globFindsMatchingPaths() throws IOException {
    Files.createDirectories(dir.resolve("sub"));
    Files.writeString(dir.resolve("sub/x.yaml"), "");
    Files.writeString(dir.resolve("y.txt"), "");

    String result = tools.glob("**/*.yaml", dir.toString());

    assertTrue(result.contains("x.yaml"));
    assertFalse(result.contains("y.txt"));
  }

  @Test
  @DisplayName("grep/glob 能搜到目录软链目标（Skill 绑定形态）")
  void grepAndGlobFollowDirectorySymlinkRoot() throws IOException {
    Path realSkill = dir.resolve("skills/report");
    Files.createDirectories(realSkill);
    Files.writeString(realSkill.resolve("SKILL.md"), "title: needle-report\n");
    Path link = dir.resolve("agents/ops/skills/report");
    Files.createDirectories(link.getParent());
    try {
      Files.createSymbolicLink(link, realSkill);
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.assumeTrue(false, "本机无法创建符号链接，跳过: " + e.getMessage());
    }

    String listing = tools.listDir(link.toString());
    assertTrue(listing.contains("SKILL.md"), "list_dir 应能列出软链目录");

    String grepped = tools.grep("needle-report", link.toString());
    assertTrue(grepped.contains("SKILL.md"), grepped);
    assertTrue(grepped.contains("needle-report"), grepped);

    String globbed = tools.glob("**/*.md", link.toString());
    assertTrue(globbed.contains("SKILL.md"), globbed);
  }

  @Test
  @DisplayName("越界会被拦：edit/grep/glob 校验不过零动作")
  void sandboxRejectionBlocksSearchAndEdit() throws IOException {
    Sandbox denying = mock(Sandbox.class);
    doThrow(new SandboxViolationException("拒绝")).when(denying).enforce(any());
    FileTools guarded = new FileTools(denying);
    Path file = dir.resolve("keep.txt");
    Files.writeString(file, "original");

    assertThrows(
        SandboxViolationException.class, () -> guarded.editFile(file.toString(), "original", "x"));
    assertThrows(SandboxViolationException.class, () -> guarded.grep("x", dir.toString()));
    assertThrows(SandboxViolationException.class, () -> guarded.glob("*", dir.toString()));
    assertEquals("original", Files.readString(file), "校验不过，文件不该被编辑");
  }
}
