package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import io.oryxos.tool.sandbox.PermissiveSandbox;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 课件《第20节》验收 harness：FileToolsTest——正常能跑通 + 越界会被拦。 */
class FileToolsTest {

  @TempDir Path dir;

  private final FileTools tools = new FileTools(new PermissiveSandbox());

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
  @DisplayName("list_dir 列出目录条目")
  void listDirShowsEntries() throws IOException {
    Files.writeString(dir.resolve("x.txt"), "");
    Files.createDirectory(dir.resolve("sub"));

    String listing = tools.listDir(dir.toString());

    assertTrue(listing.contains("x.txt"));
    assertTrue(listing.contains("sub"));
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
    assertFalse(Files.exists(target), "校验不过，文件根本不该被创建");
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
