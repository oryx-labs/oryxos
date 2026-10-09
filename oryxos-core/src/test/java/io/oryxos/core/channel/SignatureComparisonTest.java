package io.oryxos.core.channel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SignatureComparisonTest {

  private static final String SIG = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";

  @Test
  @DisplayName("相同签名判真，任一位不同判假")
  void matchesOnlyOnEquality() {
    assertTrue(SignatureComparison.constantTimeEquals(SIG, SIG));
    // 逐位改动：每一位都要判假
    for (int i = 0; i < SIG.length(); i++) {
      char replacement = SIG.charAt(i) == '0' ? '1' : '0';
      String mutated = SIG.substring(0, i) + replacement + SIG.substring(i + 1);
      assertFalse(
          SignatureComparison.constantTimeEquals(SIG, mutated), "第 " + i + " 位被改动却判真: " + mutated);
    }
  }

  @Test
  @DisplayName("前缀命中长度不同不影响结果 —— 只判真假，不给部分匹配")
  void isAllOrNothing() {
    // 共 40 位；逐个截断作为「前缀命中的探测」，全部必须判假
    for (int prefix = 0; prefix < SIG.length(); prefix++) {
      assertFalse(
          SignatureComparison.constantTimeEquals(SIG.substring(0, prefix), SIG),
          "前缀长度 " + prefix + " 被判真");
    }
  }

  @Test
  @DisplayName("长度不同判假，不抛异常")
  void lengthMismatchIsFalse() {
    assertFalse(SignatureComparison.constantTimeEquals(SIG, SIG + "0"));
    assertFalse(SignatureComparison.constantTimeEquals(SIG, ""));
  }

  @Test
  @DisplayName("null 视同空串；两个 null 判真（空==空）")
  void nullTreatedAsEmpty() {
    assertTrue(SignatureComparison.constantTimeEquals(null, null));
    assertTrue(SignatureComparison.constantTimeEquals(null, ""));
    assertFalse(SignatureComparison.constantTimeEquals(null, SIG));
    assertFalse(SignatureComparison.constantTimeEquals(SIG, null));
  }

  @Test
  @DisplayName("大小写敏感 —— 归一化由调用方决定，这里不改写输入")
  void isCaseSensitive() {
    assertFalse(SignatureComparison.constantTimeEquals(SIG, SIG.toUpperCase()));
  }

  @Test
  @DisplayName("非 ASCII 输入不会被折叠成 hex 字符而产生误判")
  void nonAsciiCannotCollideWithHex() {
    // 全角 'ａ' 等非 ASCII 走 ASCII 编码会落到 '?'(0x3F)，而 hex 里没有 '?'
    assertFalse(SignatureComparison.constantTimeEquals("\uFF41\uFF42", "ab"));
    assertFalse(
        SignatureComparison.constantTimeEquals("????????????????????????????????????????", SIG));
  }
}
