package io.oryxos.channel.wecom;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.http.WebSocket;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class WeComWsClientTest {

  @Test
  @DisplayName("onError 后清除订阅态，status 不应再报 CONNECTED")
  void onErrorClearsSubscriptionState() throws Exception {
    AtomicBoolean disconnected = new AtomicBoolean(false);
    WeComWsClient client =
        new WeComWsClient(
            "bot",
            "secret",
            WeComWsClient.DEFAULT_WS_URL,
            node -> {},
            () -> disconnected.set(true));
    var subscribed = WeComWsClient.class.getDeclaredField("subscribed");
    subscribed.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) subscribed.get(client)).set(true);
    var closed = WeComWsClient.class.getDeclaredField("closed");
    closed.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) closed.get(client)).set(false);

    client.onError(null, new RuntimeException("connection reset"));

    assertFalse(client.isSubscribed());
    assertTrue(disconnected.get());
  }

  @Test
  @DisplayName("无 cmd + errcode≠0 的回执帧_WARN 落日志不静默")
  void errorReceiptFrameIsLoggedNotSwallowed() {
    Logger logger = (Logger) LoggerFactory.getLogger(WeComWsClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      WeComWsClient client =
          new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {});
      WebSocket ws = mock(WebSocket.class);
      client.onText(ws, "{\"errcode\":0}", true);
      assertTrue(client.isSubscribed());

      client.onText(ws, "{\"errcode\":14,\"errmsg\":\"bad req_id\"}", true);

      assertTrue(
          appender.list.stream()
              .anyMatch(
                  e ->
                      e.getLevel() == Level.WARN
                          && e.getFormattedMessage().contains("errcode=14")
                          && e.getFormattedMessage().contains("bad req_id")),
          "平台错误回执必须 WARN 落日志");
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  @DisplayName("无 cmd + errcode=0 的控制帧_仍静默忽略")
  void okControlFrameStaysSilent() {
    Logger logger = (Logger) LoggerFactory.getLogger(WeComWsClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      WeComWsClient client =
          new WeComWsClient("bot", "secret", WeComWsClient.DEFAULT_WS_URL, node -> {}, () -> {});
      WebSocket ws = mock(WebSocket.class);
      client.onText(ws, "{\"errcode\":0}", true);
      int afterSubscribe = appender.list.size();

      client.onText(ws, "{\"errcode\":0}", true);

      assertTrue(appender.list.size() == afterSubscribe, "errcode=0 控制帧不得产生日志");
    } finally {
      logger.detachAppender(appender);
    }
  }
}
