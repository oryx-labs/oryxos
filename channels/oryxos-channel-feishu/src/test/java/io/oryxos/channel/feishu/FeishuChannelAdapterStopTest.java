package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lark.oapi.ws.Client;
import io.oryxos.core.channel.ChannelConfig;
import io.oryxos.core.channel.ChannelStatus;
import io.oryxos.core.channel.InboundMessageService;
import io.oryxos.core.channel.OutboundGuard;
import io.oryxos.core.profile.ProfileRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 2026-08 容器停机实录回归：SDK {@code ws.Client.close()} 存在不返回的实现路径，SIGTERM 后被它拖满 ~30s 才完成关闭。 closeQuietly
 * 必须限时——close 阻塞时 {@code stop()} 快速返回、断开动作留在守护线程，停机链路与 ChannelAdminService.stopAll 不被拖死。
 */
class FeishuChannelAdapterStopTest {

  private static final long STOP_DEADLINE_MS = 5_000;

  private FeishuChannelAdapter adapter;

  @BeforeEach
  void setUp() {
    ChannelConfig config =
        new ChannelConfig("feishu-test", "feishu", "cli_a", "sec", "demo-agent", true);
    adapter =
        new FeishuChannelAdapter(
            config,
            mock(ProfileRegistry.class),
            mock(InboundMessageService.class),
            mock(OutboundGuard.class));
  }

  @Test
  @DisplayName("SDK close 阻塞不返回时 stop() 限时返回，断开留在守护线程")
  void stopReturnsPromptlyWhenWsCloseBlocks() throws Exception {
    Client ws = mock(Client.class);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              entered.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return null;
            })
        .when(ws)
        .close();
    setWsClient(ws);

    long start = System.nanoTime();
    adapter.stop();
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertTrue(
        elapsedMs < STOP_DEADLINE_MS, () -> "stop() 不应等待阻塞的 SDK close，实际耗时 " + elapsedMs + "ms");

    assertTrue(entered.await(STOP_DEADLINE_MS, TimeUnit.MILLISECONDS), "close 应已在守护线程被调起");
    verify(ws).close();
    release.countDown();

    assertEquals(
        ChannelStatus.State.DISCONNECTED, adapter.status().state(), "关闭后渠道状态应为 DISCONNECTED");
  }

  @Test
  @DisplayName("SDK close 抛异常不破坏 stop 语义（仅告警），渠道仍置为 DISCONNECTED")
  void closeThrowingStillStopsChannel() {
    Client ws = mock(Client.class);
    doThrow(new RuntimeException("boom")).when(ws).close();
    setWsClient(ws);

    adapter.stop();

    assertEquals(ChannelStatus.State.DISCONNECTED, adapter.status().state());
    verify(ws).close();
  }

  @Test
  @DisplayName("未启动（无 ws 客户端）时 stop 安全无副作用")
  void stopWithoutStartIsSafe() {
    adapter.stop();
    assertEquals(ChannelStatus.State.DISCONNECTED, adapter.status().state());
  }

  private void setWsClient(Client ws) {
    try {
      Field field = FeishuChannelAdapter.class.getDeclaredField("wsClient");
      field.setAccessible(true);
      field.set(adapter, ws);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("注入 wsClient 失败", e);
    }
  }
}
