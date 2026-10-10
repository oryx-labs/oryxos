package io.oryxos.channel.weixinkf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖分段这一层。
 *
 * <p><b>为什么这里没有「逐段发送」的请求计数断言</b>（而 {@code MatrixMessageSenderTest} 有）： {@link
 * WeixinKfApiClient#postJson} 的目标是硬编码的 {@link WeixinKfAccessTokenClient#API_BASE}，{@code sendText}
 * 一定会去连 {@code qyapi.weixin.qq.com}。没有把 base 提成构造参数，就没有办法在不起真实网络请求的前提下 把 {@code sendText} 跑完 ——
 * 而为测试去改生产代码的构造签名不是这里该做的事。
 *
 * <p>所以这里钉住能钉的两件事：默认分段值与平台上限的关系、以及切分本身。
 */
class WeixinKfApiClientTest {

  /**
   * 企业微信官方文档对 {@code /cgi-bin/kf/send_msg} 的 {@code text.content} 写的是 「最长不超过 2048 个字节，超出部分截断」。★
   * 那是<b>字节</b>。
   */
  private static final int PLATFORM_CAP_BYTES = 2048;

  /** 中文在 UTF-8 下一个字符占 3 字节 ⇒ 2048 字节只装得下约 682 个汉字。 */
  private static final int PLATFORM_CAP_CHARS_FOR_CJK = PLATFORM_CAP_BYTES / 3;

  @Test
  @DisplayName("默认分段上限在按中文 3 字节折算后仍不超 2048 字节")
  void defaultChunkSizeFitsUnderThePlatformCap() {
    assertTrue(WeixinKfApiClient.DEFAULT_CHUNK_SIZE > 0);
    assertTrue(
        WeixinKfApiClient.DEFAULT_CHUNK_SIZE <= PLATFORM_CAP_CHARS_FOR_CJK,
        "默认分段 "
            + WeixinKfApiClient.DEFAULT_CHUNK_SIZE
            + " 字符；全中文时 "
            + (WeixinKfApiClient.DEFAULT_CHUNK_SIZE * 3)
            + " 字节，超过平台上限 "
            + PLATFORM_CAP_BYTES
            + " 字节");
  }

  @Test
  @DisplayName("默认分段也不超过上限本身（防止有人把字符数当成字节数）")
  void defaultChunkSizeIsSizedForBytesNotCharacters() {
    // 若按「2048 字符」取值，全中文时会有 6144 字节 —— 三倍超限。
    // 这条断言把「取值必须按字节折算」这件事钉住：取 2048 本身就说明折算漏了。
    assertTrue(
        WeixinKfApiClient.DEFAULT_CHUNK_SIZE < PLATFORM_CAP_BYTES,
        "分段值 " + WeixinKfApiClient.DEFAULT_CHUNK_SIZE + " 不该等于或超过字节上限本身");
  }

  @Test
  @DisplayName("超长回复被切成多段，每段不超上限，拼接后一字不差")
  void longReplyIsSplitAndReassembles() {
    String reply = "字".repeat(WeixinKfApiClient.DEFAULT_CHUNK_SIZE * 3 + 137);
    List<String> parts = WeixinKfApiClient.segment(reply, WeixinKfApiClient.DEFAULT_CHUNK_SIZE);
    assertTrue(parts.size() >= 4, "应切成多段，实际 " + parts.size());
    for (String part : parts) {
      assertTrue(part.length() <= WeixinKfApiClient.DEFAULT_CHUNK_SIZE, "有段超上限: " + part.length());
      assertTrue(
          part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= PLATFORM_CAP_BYTES,
          "有段的 UTF-8 字节数超上限: " + part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
    }
    assertEquals(reply, String.join("", parts), "分段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只切一段，不额外分包")
  void shortReplyStaysWhole() {
    assertEquals(
        List.of("你好"), WeixinKfApiClient.segment("你好", WeixinKfApiClient.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("空文本仍产出一段（保持「必有回复」语义）")
  void emptyYieldsOneEmptyPart() {
    assertEquals(List.of(""), WeixinKfApiClient.segment("", WeixinKfApiClient.DEFAULT_CHUNK_SIZE));
    assertEquals(
        List.of(""), WeixinKfApiClient.segment(null, WeixinKfApiClient.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("代理对不被劈开（emoji 不会变成两个问号）")
  void surrogatePairsAreNotSplit() {
    // 同 Teams 那个测试：不能用 chunkSize=1（split 有「退无可退」分支会故意拆开）。
    List<String> parts = WeixinKfApiClient.segment("a😀b", 2);
    for (String part : parts) {
      assertTrue(
          part.isEmpty() || !Character.isHighSurrogate(part.charAt(part.length() - 1)),
          "有段以孤立高代理结尾: " + part);
    }
    assertEquals("a😀b", String.join("", parts));
    assertTrue(parts.contains("😀"), "emoji 应完整落在某一段里，实际: " + parts);
  }
}
