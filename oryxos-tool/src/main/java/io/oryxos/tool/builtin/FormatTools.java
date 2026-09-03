package io.oryxos.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileOutputStream;
import java.io.IOException;
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
 *   <li>{@code export_excel} - 将数据导出为 Excel 文件
 * </ul>
 */
public class FormatTools {

  private static final Logger LOG = LoggerFactory.getLogger(FormatTools.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

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
    } catch (Exception e) {
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

      exportToExcel(filePath, sheetName, headers, rows);
      return "Excel 文件已导出到: " + filePath;
    } catch (Exception e) {
      LOG.error("导出 Excel 失败", e);
      return "导出失败: " + e.getMessage();
    }
  }

  private String formatAsMarkdownTable(List<String> headers, List<List<String>> rows) {
    StringBuilder sb = new StringBuilder();

    // 表头
    sb.append("| ");
    sb.append(String.join(" | ", headers));
    sb.append(" |\n");

    // 分隔线
    sb.append("| ");
    sb.append(String.join(" | ", headers.stream().map(h -> "---").toList()));
    sb.append(" |\n");

    // 数据行
    for (List<String> row : rows) {
      sb.append("| ");
      sb.append(String.join(" | ", row));
      sb.append(" |\n");
    }

    return sb.toString();
  }

  private void exportToExcel(
      String filePath, String sheetName, List<String> headers, List<List<String>> rows)
      throws IOException {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet(sheetName);

      // 写入表头
      Row headerRow = sheet.createRow(0);
      for (int i = 0; i < headers.size(); i++) {
        Cell cell = headerRow.createCell(i);
        cell.setCellValue(headers.get(i));
      }

      // 写入数据行
      int rowNum = 1;
      for (List<String> rowData : rows) {
        Row row = sheet.createRow(rowNum++);
        for (int i = 0; i < rowData.size(); i++) {
          Cell cell = row.createCell(i);
          cell.setCellValue(rowData.get(i));
        }
      }

      // 自动调整列宽
      for (int i = 0; i < headers.size(); i++) {
        sheet.autoSizeColumn(i);
      }

      // 写入文件
      try (FileOutputStream outputStream = new FileOutputStream(filePath)) {
        workbook.write(outputStream);
      }
    }
  }
}
