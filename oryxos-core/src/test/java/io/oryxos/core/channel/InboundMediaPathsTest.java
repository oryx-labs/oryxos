package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboundMediaPathsTest {

  private static final Path MEDIA_ROOT = Path.of("/tmp/oryxos-media/channelX");

  @Test
  @DisplayName("safeSegment 的结果必须是名字，不能是目录引用")
  void neverReturnsADirectoryReference() {
    // "." 与 ".." 只由白名单字符组成，但如果被当成段 resolve，就会落到 root 之外。
    for (String raw : new String[] {".", "..", "...", "...."}) {
      String seg = InboundMediaPaths.safeSegment(raw);
      Path resolved = MEDIA_ROOT.resolve(seg).normalize();
      assertTrue(
          resolved.startsWith(MEDIA_ROOT.normalize()),
          "safeSegment(\"%s\") = \"%s\"，resolve 之后逃出了 %s：%s"
              .formatted(raw, seg, MEDIA_ROOT, resolved));
    }
  }

  @Test
  @DisplayName("safeSegment：穿越形态的输入都被中和")
  void neutralisesTraversalShapes() {
    // 已有测试覆盖了这两个；保留在这里是为了让本类的意图完整。
    assertNotEquals("../evil", InboundMediaPaths.safeSegment("../evil"));
    assertTrue(InboundMediaPaths.safeSegment("img/../x").contains("_"));

    // 分隔符被替换后不再是上跳序列 —— 只是一个普通名字。
    assertEquals(".._..", InboundMediaPaths.safeSegment("../.."));
    assertEquals("a_.._.._b", InboundMediaPaths.safeSegment("a/../../b"));
  }

  @Test
  @DisplayName("safeSegment：超长输入不能把两个不同的 id 塌成同一个段")
  void truncationKeepsDistinctIdsDistinct() {
    String prefix = "m".repeat(96);
    String a = prefix + "AAAA";
    String b = prefix + "BBBB";

    assertNotEquals(
        InboundMediaPaths.safeSegment(a),
        InboundMediaPaths.safeSegment(b),
        "前 96 位相同的两个 id 得到了同一个段 —— 两个消息会共用同一目录");

    // 截断仍然生效：结果不比上限长。
    assertTrue(InboundMediaPaths.safeSegment(a).length() <= 96);
    assertTrue(InboundMediaPaths.safeSegment(b).length() <= 96);
  }

  @Test
  @DisplayName("safeSegment：确定性 —— 同一个输入永远同一个输出")
  void isDeterministic() {
    String longId = "x".repeat(200);
    assertEquals(InboundMediaPaths.safeSegment(longId), InboundMediaPaths.safeSegment(longId));
    assertEquals("x", InboundMediaPaths.safeSegment("???"));
    assertEquals("x", InboundMediaPaths.safeSegment(null));
    assertEquals("x", InboundMediaPaths.safeSegment("   "));
  }

  @Test
  @DisplayName("safeSegment 的输出总能安全 resolve 到给定根之下")
  void outputAlwaysStaysUnderTheRoot() {
    String[] hostile = {
      "..", ".", "../..", "..\\..", "a/../../b", "\u0000", "con", "..%2f..", ".._..",
    };
    for (String raw : hostile) {
      String seg = InboundMediaPaths.safeSegment(raw);
      assertFalse(seg.contains("/"), "段里不该出现分隔符: " + seg);
      assertFalse(seg.contains("\\"), "段里不该出现反斜杠: " + seg);
      assertTrue(
          MEDIA_ROOT.resolve(seg).normalize().startsWith(MEDIA_ROOT.normalize()),
          "resolve 之后逃出了根: " + raw + " → " + seg);
    }
  }
}
