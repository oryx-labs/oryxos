package io.oryxos.channel.wecom;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeComProgressStreamTest {

  private WeComMessageSender sender;
  private WeComProgressStream stream;

  @BeforeEach
  void setUp() {
    sender = mock(WeComMessageSender.class);
    stream = new WeComProgressStream(sender, "chat-1", "msg-1");
  }

  @Test
  @DisplayName("start 发思考占位；首个 tool 发一条进度；再 finish；token 不刷屏")
  void startFinishIgnoresTokens() {
    stream.start();
    stream.onToken("a");
    stream.onToolStart("http_get");
    stream.onToolStart("read_file");
    stream.finish("最终答案");

    verify(sender).send(eq("chat-1"), eq(WeComProgressStream.THINKING_REPLY), eq("msg-1"));
    verify(sender).send(eq("chat-1"), eq("🔧 正在执行 `http_get` …"), eq("msg-1"));
    verify(sender).send(eq("chat-1"), eq("最终答案"), eq("msg-1"));
    verify(sender, times(3))
        .send(eq("chat-1"), org.mockito.ArgumentMatchers.anyString(), eq("msg-1"));
  }

  @Test
  @DisplayName("fail 发失败文案且幂等")
  void failOnce() {
    stream.start();
    stream.fail("boom");
    stream.fail("again");

    verify(sender).send(eq("chat-1"), eq(WeComProgressStream.THINKING_REPLY), eq("msg-1"));
    verify(sender).send(eq("chat-1"), eq("boom"), eq("msg-1"));
    verify(sender, times(2))
        .send(eq("chat-1"), org.mockito.ArgumentMatchers.anyString(), eq("msg-1"));
  }
}
