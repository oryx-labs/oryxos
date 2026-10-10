package io.oryxos.channel.qq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 覆盖分段这一层。
 *
 * <p><b>这里的「上限」不是官方文档给的</b>，与同族其它渠道不同 —— QQ 官方没有公布消息长度 上限（字段页对 {@code content} 只说「文本内容」，错误码 {@code
 * 40054007} / {@code 40054018} 只报"超限"不给数字）。本测试钉住的 15000 是 2026-10-10 的真机实测结果：HarmonyOS 版 QQ 上 15000
 * 完整显示、16000 起看不到。
 *
 * <p><b>为什么没有请求计数断言</b>：{@code QqAccessTokenClient.API_BASE_URL} 是硬编码的 {@code
 * https://api.bot.qq.com}，{@code send} 一定会打真实端点；与 Teams 同样的理由， 不为测试改生产代码的构造签名。
 */
class QqMessageSenderTest {

  /**
   * 真机实测到的客户端渲染边界（HarmonyOS 版 QQ，2026-10-10）。
   *
   * <p>注意它不是平台常量：API 侧接受 100 万字符，这个数字来自**客户端能否完整显示**。
   */
  private static final int MEASURED_CLIENT_LIMIT = 15000;

  @Test
  @DisplayName("默认分段值不超过实测到的客户端边界")
  void defaultChunkSizeFitsUnderTheMeasuredClientLimit() {
    assertTrue(QqMessageSender.DEFAULT_CHUNK_SIZE > 0);
    assertTrue(
        QqMessageSender.DEFAULT_CHUNK_SIZE <= MEASURED_CLIENT_LIMIT,
        "默认分段 " + QqMessageSender.DEFAULT_CHUNK_SIZE + " 超过实测客户端边界 " + MEASURED_CLIENT_LIMIT);
  }

  @Test
  @DisplayName("默认分段值取在实测边界上 —— 那是唯一有实测支持的数字")
  void defaultChunkSizeSitsOnTheMeasuredLimit() {
    // 取到边界而非留「余量」，是刻意的：实测确认 15000 能完整显示、16000 起看不到，
    // 所以 15000 是有证据的最大值；比它小的值（12000 之类）里那点余量只是对
    // 其它客户端（iOS / Android / PC）的推测 —— 推测不比实测硬。
    //
    // 这条断言把那个决定钉住：若有人事后想把值调小，会在这里看到理由。
    assertEquals(MEASURED_CLIENT_LIMIT, QqMessageSender.DEFAULT_CHUNK_SIZE, "若没有新的实测，分段值应保持在实测边界上");
  }

  @Test
  @DisplayName("超长回复被切成多段，每段不超上限，拼接后一字不差")
  void longReplyIsSplitAndReassembles() {
    String reply = "字".repeat(QqMessageSender.DEFAULT_CHUNK_SIZE * 2 + 137);
    List<String> parts = QqMessageSender.segment(reply, QqMessageSender.DEFAULT_CHUNK_SIZE);
    assertTrue(parts.size() >= 3, "应切成多段，实际 " + parts.size());
    for (String part : parts) {
      assertTrue(part.length() <= QqMessageSender.DEFAULT_CHUNK_SIZE, "有段超上限: " + part.length());
    }
    assertEquals(reply, String.join("", parts), "分段拼接必须与原回复一致");
  }

  @Test
  @DisplayName("短回复只切一段，不额外分包")
  void shortReplyStaysWhole() {
    assertEquals(List.of("你好"), QqMessageSender.segment("你好", QqMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("空文本仍产出一段（保持「必有回复」语义）")
  void emptyYieldsOneEmptyPart() {
    assertEquals(List.of(""), QqMessageSender.segment("", QqMessageSender.DEFAULT_CHUNK_SIZE));
    assertEquals(List.of(""), QqMessageSender.segment(null, QqMessageSender.DEFAULT_CHUNK_SIZE));
  }

  @Test
  @DisplayName("代理对不被劈开（emoji 不会变成两个问号）")
  void surrogatePairsAreNotSplit() {
    // 同其它渠道：不能用 chunkSize=1（split 有「退无可退」分支会故意拆开）。
    List<String> parts = QqMessageSender.segment("a😀b", 2);
    for (String part : parts) {
      assertTrue(
          part.isEmpty() || !Character.isHighSurrogate(part.charAt(part.length() - 1)),
          "有段以孤立高代理结尾: " + part);
    }
    assertEquals("a😀b", String.join("", parts));
    assertTrue(parts.contains("😀"), "emoji 应完整落在某一段里，实际: " + parts);
  }
}
