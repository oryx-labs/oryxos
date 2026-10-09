package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FormatToolsTest {

  @Test
  @DisplayName("format_sql 输出 Markdown 表头与分隔线")
  void formatSqlMarkdown() {
    FormatTools tools = new FormatTools(mock(Sandbox.class));
    String out =
        tools.formatSql("{\"headers\":[\"a\",\"b\"],\"rows\":[[\"1\",\"2\"],[\"3\",\"4\"]]}");
    assertTrue(out.contains("| a | b |"));
    assertTrue(out.contains("| --- | --- |"));
    assertTrue(out.contains("| 1 | 2 |"));
  }

  @Test
  @DisplayName("export_excel 写前 enforce FILE_WRITE；拒绝时不写盘")
  void exportExcelEnforcesSandbox() {
    Sandbox sandbox = mock(Sandbox.class);
    doThrow(new IllegalStateException("path not allowed"))
        .when(sandbox)
        .enforce(any(SandboxAction.class));
    FormatTools tools = new FormatTools(sandbox);
    String out =
        tools.exportExcel(
            "{\"file_path\":\"C:\\\\tmp\\\\out.xlsx\",\"headers\":[\"a\"],\"rows\":[[\"1\"]]}");
    assertTrue(out.startsWith("导出失败"));
    verify(sandbox).enforce(any(SandboxAction.class));
  }

  @Test
  @DisplayName("export_excel 不得写保留文件 —— 与 write_file 同一道守卫")
  void exportExcelRejectsReservedFiles() throws IOException {
    Path dir = Files.createTempDirectory("format-reserved");
    // 沙箱放行，只考察保留文件守卫本身
    FormatTools tools = new FormatTools(mock(Sandbox.class));

    List<String> reserved =
        List.of(
            dir.resolve("agents/demo/MEMORY.md").toString(),
            dir.resolve("agents/demo/AGENT.md").toString(),
            dir.resolve("skills/report/SKILL.md").toString(),
            dir.resolve("knowledge/ops/doc.md").toString(),
            dir.resolve("channels.yaml").toString());

    for (String path : reserved) {
      String payload =
          "{\"file_path\":\""
              + path.replace("\\", "/")
              + "\",\"sheet_name\":\"s\",\"headers\":[\"a\"],\"rows\":[[\"1\"]]}";
      String out = tools.exportExcel(payload);
      assertTrue(out.startsWith("导出失败"), "保留路径应被拒绝，实际返回: " + out + "  path=" + path);
      assertFalse(Files.exists(Path.of(path)), "保留文件被写到盘上了: " + path);
    }
  }

  @Test
  @DisplayName("export_excel 写普通路径仍然成功（守卫不能误伤）")
  void exportExcelStillWritesNormalPath() throws IOException {
    Path dir = Files.createTempDirectory("format-normal");
    Path out = dir.resolve("report.xlsx");
    FormatTools tools = new FormatTools(mock(Sandbox.class));
    String payload =
        "{\"file_path\":\""
            + out.toString().replace("\\", "/")
            + "\",\"sheet_name\":\"s\",\"headers\":[\"a\"],\"rows\":[[\"1\"]]}";
    String result = tools.exportExcel(payload);
    assertTrue(result.startsWith("Excel 文件已导出到"), "普通路径应可写: " + result);
    assertTrue(Files.exists(out), "普通路径应真的写盘了");
  }
}
