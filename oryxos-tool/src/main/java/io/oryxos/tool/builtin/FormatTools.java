package io.oryxos.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 格式化工具：提供 SQL 结果表格化和 Excel 导出功能。
 *
 * <p>包含两个工具：
 *
 * <ul>
 *   <li>{@code format_sql} - 将 SQL 查询结果格式化为 Markdown 表格
 *   <li>{@code export_excel} - 将数据导出为 Excel 文件（写路径必须过 FILE 沙箱白名单）
 * </ul>
 */
public class FormatTools {

  private static final Logger LOG = LoggerFactory.getLogger(FormatTools.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Sandbox sandbox;

  public FormatTools(Sandbox sandbox) {
    this.sandbox = sandbox;
  }

  @Tool(
      name = "format_sql",
      description =
          "将 SQL 查询结果格式化为 Markdown 表格。输入 JSON 格式：{\"headers\": [\"列1\", \"列2\"], \"rows\": [[\"值1\", \"值2\"]]}")
  public String formatSql(
      @ToolParam(description = "JSON 格式的查询结果，包含 headers 和 rows 字段") String resultJson) {
    try {
      JsonNode root = MAPPER.readTree(resultJson);
      JsonNode headersNode = root.get("headers");
      JsonNode rowsNode = root.get("rows");

      if (headersNode == null || !headersNode.isArray()) {
        return "错误: 缺少 headers 参数或格式错误";
      }
      if (rowsNode == null || !rowsNode.isArray()) {
        return "错误: 缺少 rows 参数或格式错误";
      }

      List<String> headers = new ArrayList<>();
      for (JsonNode header : headersNode) {
        headers.add(header.asText());
      }

      List<List<String>> rows = new ArrayList<>();
      for (JsonNode rowNode : rowsNode) {
        List<String> row = new ArrayList<>();
        for (JsonNode cell : rowNode) {
          row.add(cell.asText(""));
        }
        rows.add(row);
      }

      return formatAsMarkdownTable(headers, rows);
    } catch (JsonProcessingException | RuntimeException e) {
      LOG.error("格式化 SQL 结果失败", e);
      return "格式化失败: " + e.getMessage();
    }
  }

  @Tool(
      name = "export_excel",
      description =
          "将数据导出为 Excel 文件。输入 JSON 格式：{\"file_path\": \"/path/to/file.xlsx\", \"sheet_name\": \"Sheet1\", \"headers\": [\"列1\"], \"rows\": [[\"值1\"]]}")
  public String exportExcel(
      @ToolParam(description = "JSON 格式的导出参数，包含 file_path, sheet_name, headers, rows")
          String paramsJson) {
    try {
      JsonNode root = MAPPER.readTree(paramsJson);
      String filePath = root.path("file_path").asText();
      String sheetName = root.path("sheet_name").asText("Sheet1");
      JsonNode headersNode = root.get("headers");
      JsonNode rowsNode = root.get("rows");

      if (filePath == null || filePath.isBlank()) {
        return "错误: 缺少 file_path 参数";
      }
      if (headersNode == null || !headersNode.isArray()) {
        return "错误: 缺少 headers 参数或格式错误";
      }
      if (rowsNode == null || !rowsNode.isArray()) {
        return "错误: 缺少 rows 参数或格式错误";
      }

      // 写前校验 + 落盘前复检（与 write_file / download_file 同款，防 TOCTOU）
      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, filePath));

      List<String> headers = new ArrayList<>();
      for (JsonNode header : headersNode) {
        headers.add(header.asText());
      }

      List<List<String>> rows = new ArrayList<>();
      for (JsonNode rowNode : rowsNode) {
        List<String> row = new ArrayList<>();
        for (JsonNode cell : rowNode) {
          row.add(cell.asText(""));
        }
        rows.add(row);
      }

      sandbox.enforce(new SandboxAction(ActionType.FILE_WRITE, filePath));
      exportToExcel(Path.of(filePath), sheetName, headers, rows);
      return "Excel 文件已导出到: " + filePath;
    } catch (IOException | RuntimeException e) {
      LOG.error("导出 Excel 失败", e);
      return "导出失败: " + e.getMessage();
    }
  }

  private static String formatAsMarkdownTable(List<String> headers, List<List<String>> rows) {
    StringBuilder sb = new StringBuilder();

    sb.append("| ");
    sb.append(String.join(" | ", headers));
    sb.append(" |\n");

    sb.append("| ");
    sb.append(String.join(" | ", headers.stream().map(h -> "---").toList()));
    sb.append(" |\n");

    for (List<String> row : rows) {
      sb.append("| ");
      sb.append(String.join(" | ", row));
      sb.append(" |\n");
    }

    return sb.toString();
  }

  private static void exportToExcel(
      Path file, String sheetName, List<String> headers, List<List<String>> rows)
      throws IOException {
    Path parent = file.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet(sheetName);

      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) {
        Cell cell = headerRow.createCell(i);
        cell.setCellValue(headers.get(i));
      }

      int rowNum = 1;
      for (List<String> rowData : rows) {
        Row row = sheet.createRow(rowNum);
        rowNum++;
        for (int i = 0; i < rowData.size(); i++) {
          Cell cell = row.createCell(i);
          cell.setCellValue(rowData.get(i));
        }
      }

      for (int i = 0; i < headers.size(); i++) {
        sheet.autoSizeColumn(i);
      }

      try (OutputStream outputStream = Files.newOutputStream(file)) {
        workbook.write(outputStream);
      }
    }
  }
}
