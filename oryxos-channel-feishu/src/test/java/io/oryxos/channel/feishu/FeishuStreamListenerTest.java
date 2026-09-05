package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.agent.ReActLoop;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuStreamListenerTest {

  private FeishuMessageSender sender;
  private FeishuStreamListener listener;

  @BeforeEach
  void setUp() {
    sender = mock(FeishuMessageSender.class);
    when(sender.sendInteractive(anyString(), anyString(), any())).thenReturn("om_card_1");
    listener = new FeishuStreamListener(sender, "oc_chat", null);
  }

  @Test
  @DisplayName("start 发蓝卡；finish 绿卡含最终答案")
  void startAndFinish() {
    listener.start();
    listener.onToken("你好");
    listener.onToken("世界");
    listener.finish("你好世界");

    ArgumentCaptor<String> createCard = ArgumentCaptor.forClass(String.class);
    verify(sender).sendInteractive(eq("oc_chat"), createCard.capture(), eq(null));
    assertTrue(createCard.getValue().contains("blue"));
    assertTrue(createCard.getValue().contains("正在思考"));

    ArgumentCaptor<String> patchCard = ArgumentCaptor.forClass(String.class);
    verify(sender, times(1)).patchInteractive(eq("om_card_1"), patchCard.capture());
    String last = patchCard.getValue();
    assertTrue(last.contains("green"));
    assertTrue(last.contains("你好世界"));
  }

  @Test
  @DisplayName("工具起止立刻 patch；fail 红卡")
  void toolsAndFail() {
    listener.start();
    listener.onToolStart("http_get");
    listener.onToolEnd("http_get", true);
    listener.fail("抱歉，这次处理失败了，请稍后重试或联系管理员。");

    ArgumentCaptor<String> patches = ArgumentCaptor.forClass(String.class);
    verify(sender, times(3)).patchInteractive(eq("om_card_1"), patches.capture());
    assertTrue(patches.getAllValues().get(0).contains("http_get"));
    assertTrue(patches.getAllValues().get(1).contains("✅"));
    String failed = patches.getAllValues().get(2);
    assertTrue(failed.contains("red"));
    assertTrue(failed.contains("处理失败"));
  }

  @Test
  @DisplayName("中断 fail 用「已停止」标题")
  void failInterruptedUsesStoppedTitle() {
    listener.start();
    listener.fail(ReActLoop.INTERRUPTED_REPLY);

    ArgumentCaptor<String> patches = ArgumentCaptor.forClass(String.class);
    verify(sender).patchInteractive(eq("om_card_1"), patches.capture());
    assertTrue(patches.getValue().contains("red"));
    assertTrue(patches.getValue().contains("已停止"));
    assertTrue(patches.getValue().contains(ReActLoop.INTERRUPTED_REPLY));
  }

  @Test
  @DisplayName("首 token 前 idle heartbeat 会 patch「仍在等待」")
  void idleHeartbeatPatchesWhileWaiting() throws Exception {
    listener = new FeishuStreamListener(sender, "oc_chat", null, Duration.ofMillis(80));
    listener.start();
    Thread.sleep(220);
    listener.finish("done");

    ArgumentCaptor<String> patches = ArgumentCaptor.forClass(String.class);
    verify(sender, atLeast(1)).patchInteractive(eq("om_card_1"), patches.capture());
    assertTrue(
        patches.getAllValues().stream().anyMatch(p -> p.contains("仍在等待模型输出")),
        "idle heartbeat must refresh placeholder");
    assertTrue(patches.getAllValues().get(patches.getAllValues().size() - 1).contains("done"));
  }

  @Test
  @DisplayName("卡片 JSON 含 header template 与 lark_md")
  void cardShape() {
    String json = FeishuProgressCard.build("回答", FeishuProgressCard.TEMPLATE_GREEN, "hi");
    assertTrue(json.contains("\"template\":\"green\""));
    assertTrue(json.contains("lark_md"));
    assertTrue(json.contains("hi"));
    assertEquals(true, json.contains("\"content\":\"回答\""));
  }
}
