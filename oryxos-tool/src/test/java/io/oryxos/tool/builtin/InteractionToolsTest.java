package io.oryxos.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.tool.interaction.ConsoleUserInteraction;
import io.oryxos.tool.interaction.InteractionUnavailableException;
import io.oryxos.tool.interaction.UnsupportedUserInteraction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ask_user 工具（human-in-the-loop 落点）测试。 */
class InteractionToolsTest {

  @Test
  @DisplayName("ask_user 在 CLI 场景读回用户输入的一行")
  void askUserReadsConsoleLine() {
    var in = new ByteArrayInputStream("周三下午\n".getBytes(StandardCharsets.UTF_8));
    var out = new ByteArrayOutputStream();
    InteractionTools tools =
        new InteractionTools(
            new ConsoleUserInteraction(in, new PrintStream(out, true, StandardCharsets.UTF_8)));

    String answer = tools.askUser("你希望什么时候开会？");

    assertEquals("周三下午", answer);
    assertTrue(out.toString(StandardCharsets.UTF_8).contains("你希望什么时候开会？"));
  }

  @Test
  @DisplayName("无交互终端的渠道_ask_user 明确报不支持不静默卡住")
  void askUserFailsExplicitlyWhenNoTerminal() {
    InteractionTools tools = new InteractionTools(new UnsupportedUserInteraction());

    assertThrows(InteractionUnavailableException.class, () -> tools.askUser("确认删除吗？"));
  }

  @Test
  @DisplayName("输入流结束（EOF）_报不支持而非返回 null")
  void askUserOnEofFails() {
    var in = new ByteArrayInputStream(new byte[0]);
    var out = new ByteArrayOutputStream();
    InteractionTools tools =
        new InteractionTools(
            new ConsoleUserInteraction(in, new PrintStream(out, true, StandardCharsets.UTF_8)));

    assertThrows(InteractionUnavailableException.class, () -> tools.askUser("在吗？"));
  }
}
