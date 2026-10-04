package io.oryxos.core.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 出站分段不得把代理对劈开 —— 否则 UTF-8 编码会把两个孤立代理都写成 '?'。 */
class OutboundTextSegmentsTest {

  @Test
  @DisplayName("切点落在代理对中间时往回收一格，emoji 完整")
  void neverSplitsASurrogatePair() {
    // 切点 10 正好落在 emoji 中间（emoji 占两个 char）
    String text = "a".repeat(9) + "😀" + "b";
    List<String> parts = OutboundTextSegments.split(text, 10);

    assertThat(parts).hasSize(2);
    assertThat(parts.get(0)).isEqualTo("a".repeat(9));
    assertThat(parts.get(1)).isEqualTo("😀b");
    // 拼回来一字不差
    assertThat(String.join("", parts)).isEqualTo(text);
    // 每一段单独编码都不产生 '?'
    for (String part : parts) {
      assertThat(new String(part.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
          .as("段落单独走 UTF-8 往返不应出现替换字符")
          .doesNotContain("?")
          .isEqualTo(part);
    }
  }

  @Test
  @DisplayName("旧写法（纯字符下标）会把 emoji 变成两个问号 —— 这就是被修掉的行为")
  void oldApproachWouldMangleTheEmoji() {
    String text = "a".repeat(9) + "😀" + "b";
    int chunkSize = 10;
    String naive0 = text.substring(0, chunkSize);
    assertThat(Character.isHighSurrogate(naive0.charAt(naive0.length() - 1))).isTrue();
    // 孤立高代理经 UTF-8 变成 '?'
    byte[] bytes = naive0.getBytes(StandardCharsets.UTF_8);
    assertThat(bytes[bytes.length - 1]).isEqualTo((byte) '?');
  }

  @Test
  @DisplayName("切点恰好落在字符边界时不回退，段长仍是 chunkSize")
  void keepsFullChunksWhenTheBoundaryIsClean() {
    String text = "abcdefghij"; // 全是 BMP 字符
    List<String> parts = OutboundTextSegments.split(text, 4);
    assertThat(parts).containsExactly("abcd", "efgh", "ij");
  }

  @Test
  @DisplayName("chunkSize 为 1 且碰上代理对：不原地打转，宁可拆开也要推进")
  void makesProgressEvenWithChunkSizeOne() {
    String text = "😀";
    List<String> parts = OutboundTextSegments.split(text, 1);
    assertThat(String.join("", parts)).isEqualTo(text);
  }

  @Test
  @DisplayName("空文本返回一个空段（保持「必有回复」语义），null 同样")
  void emptyTextYieldsOneEmptyPart() {
    assertThat(OutboundTextSegments.split("", 100)).containsExactly("");
    assertThat(OutboundTextSegments.split(null, 100)).containsExactly("");
  }

  @Test
  @DisplayName("truncate 不劈开代理对")
  void truncateKeepsPairsWhole() {
    String text = "a".repeat(9) + "😀";
    assertThat(OutboundTextSegments.truncate(text, 10)).isEqualTo("a".repeat(9));
    assertThat(OutboundTextSegments.truncate(text, 11)).isEqualTo(text);
    assertThat(OutboundTextSegments.truncate("短", 10)).isEqualTo("短");
  }
}
