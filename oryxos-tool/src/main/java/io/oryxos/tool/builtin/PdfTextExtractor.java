package io.oryxos.tool.builtin;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 文本型 PDF 抽文本（供 {@code read_file}）。扫描件（无文本层）抛出带原因的 {@link IOException}。
 *
 * <p>逻辑对齐 knowledge {@code PdfParser}，但不依赖 knowledge 模块（避免 tool→knowledge 反向依赖）。
 */
final class PdfTextExtractor {

  /** 单次返回字符上限，避免大 PDF 撑爆 Agent 上下文。 */
  static final int MAX_CHARS = 100_000;

  private PdfTextExtractor() {}

  static String extract(Path file) throws IOException {
    try (PDDocument document = Loader.loadPDF(file.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      StringBuilder all = new StringBuilder();
      boolean hasText = false;
      int pages = document.getNumberOfPages();
      for (int page = 1; page <= pages; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        String text = stripper.getText(document);
        if (text != null && !text.isBlank()) {
          hasText = true;
          if (!all.isEmpty()) {
            all.append("\n\n--- page ").append(page).append(" ---\n\n");
          } else if (pages > 1) {
            all.append("--- page ").append(page).append(" ---\n\n");
          }
          all.append(text.strip());
          if (all.length() >= MAX_CHARS) {
            return all.substring(0, MAX_CHARS) + "\n\n…(已截断，read_file 上限 " + MAX_CHARS + " 字符)";
          }
        }
      }
      if (!hasText) {
        throw new IOException(
            "PDF 无文本层（疑似扫描件），无法用 read_file 抽取。请提供文本型 PDF 或先做 OCR: " + file.getFileName());
      }
      return all.toString();
    }
  }
}
