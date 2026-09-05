package io.oryxos.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InboundMediaExtTest {

  @TempDir Path dir;

  @Test
  @DisplayName("%PDF 魔数识别")
  void detectsPdfMagic() throws IOException {
    Path pdf = dir.resolve("a.bin");
    Files.write(pdf, "%PDF-1.4 hello".getBytes(StandardCharsets.US_ASCII));
    assertTrue(InboundMediaExt.isPdfMagic(pdf));
    assertEquals(InboundMediaExt.EXT_PDF, InboundMediaExt.betterFileExtension(pdf, ".bin"));
    assertNull(InboundMediaExt.betterFileExtension(pdf, ".pdf"));
  }

  @Test
  @DisplayName("OggS 魔数识别为 .ogg")
  void detectsOggMagic() throws IOException {
    Path ogg = dir.resolve("voice.bin");
    Files.write(ogg, "OggS....".getBytes(StandardCharsets.US_ASCII));
    assertTrue(InboundMediaExt.isOggMagic(ogg));
    assertEquals(InboundMediaExt.EXT_OGG, InboundMediaExt.betterFileExtension(ogg, ".bin"));
  }

  @Test
  @DisplayName("Silk 魔数识别为 .silk")
  void detectsSilkMagic() throws IOException {
    Path silk = dir.resolve("voice.bin");
    Files.write(silk, "#!SILK_V3....".getBytes(StandardCharsets.US_ASCII));
    assertTrue(InboundMediaExt.isSilkMagic(silk));
    assertEquals(InboundMediaExt.EXT_SILK, InboundMediaExt.betterFileExtension(silk, ".bin"));
  }

  @Test
  @DisplayName("AMR 魔数识别为 .amr")
  void detectsAmrMagic() throws IOException {
    Path amr = dir.resolve("voice.bin");
    Files.write(amr, "#!AMR\n....".getBytes(StandardCharsets.US_ASCII));
    assertTrue(InboundMediaExt.isAmrMagic(amr));
    assertEquals(InboundMediaExt.EXT_AMR, InboundMediaExt.betterFileExtension(amr, ".bin"));
  }

  @Test
  @DisplayName("非 PDF 占位扩展名保持")
  void nonPdfPlaceholder() throws IOException {
    Path other = dir.resolve("b.bin");
    Files.write(other, "not-a-pdf".getBytes(StandardCharsets.US_ASCII));
    assertFalse(InboundMediaExt.isPdfMagic(other));
    assertNull(InboundMediaExt.betterFileExtension(other, ".bin"));
  }

  @Test
  @DisplayName("后缀 .pdf 判定")
  void hasPdfExtension() {
    assertTrue(InboundMediaExt.hasPdfExtension(Path.of("report.PDF")));
    assertFalse(InboundMediaExt.hasPdfExtension(Path.of("report.txt")));
  }
}
