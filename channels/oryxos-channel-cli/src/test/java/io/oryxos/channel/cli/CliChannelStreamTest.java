package io.oryxos.channel.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.oryxos.channel.cli.CliChannel.TypewriterListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 019 验收 harness：CliChannelStreamTest——终端打字机 listener 口径钉死（FR-015/R8）。守：token 逐段打印顺序、
 * 工具状态提示行、printedAny 判定（供回落整段输出）。
 */
class CliChannelStreamTest {

  @Test
  @DisplayName("token逐段打印_顺序与回调一致_printedAny为真")
  void tokensPrintedInOrder() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    TypewriterListener listener =
        new TypewriterListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

    listener.onToken("你好");
    listener.onToken("，世界");

    assertThat(buffer.toString(StandardCharsets.UTF_8)).isEqualTo("你好，世界");
    assertThat(listener.printedAny()).isTrue();
  }

  @Test
  @DisplayName("工具调用_状态提示行出现且成败可辨")
  void toolHintsPrinted() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    TypewriterListener listener =
        new TypewriterListener(new PrintStream(buffer, true, StandardCharsets.UTF_8));

    listener.onToolStart("shell");
    listener.onToolEnd("shell", true);
    listener.onToolStart("http_get");
    listener.onToolEnd("http_get", false);
    listener.onToken("最终回复");

    String output = buffer.toString(StandardCharsets.UTF_8);
    assertThat(output)
        .contains("[调用工具 shell …]")
        .contains("[工具 shell 完成]")
        .contains("[工具 http_get 失败]")
        .endsWith("最终回复");
  }

  @Test
  @DisplayName("无token流出_printedAny为假（CliChannel据此回落整段输出）")
  void noTokens_printedAnyFalse() {
    TypewriterListener listener =
        new TypewriterListener(
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

    assertThat(listener.printedAny()).isFalse();
  }
}
