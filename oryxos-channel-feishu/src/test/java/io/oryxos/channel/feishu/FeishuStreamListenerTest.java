package io.oryxos.channel.feishu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FeishuStreamListenerTest {

  @Mock private FeishuMessageSender sender;
  @Mock private FeishuCardBuilder cardBuilder;

  private FeishuStreamListener listener;

  @BeforeEach
  void setUp() {
    listener = new FeishuStreamListener(sender, cardBuilder, "test_chat_id", "test_reply_to");
  }

  @Test
  void testStart() {
    // Given
    when(cardBuilder.buildInitialCard()).thenReturn("{\"card\":\"initial\"}");
    when(sender.sendCard("test_chat_id", "{\"card\":\"initial\"}", "test_reply_to"))
        .thenReturn("card_msg_id");

    // When
    listener.start();

    // Then
    verify(cardBuilder).buildInitialCard();
    verify(sender).sendCard("test_chat_id", "{\"card\":\"initial\"}", "test_reply_to");
  }

  @Test
  void testOnToken() {
    // Given
    listener.start();

    // When
    listener.onToken("Hello");
    listener.onToken(" World");

    // Then - 累积但不立即更新（等工具调用或完成）
    // 实际更新逻辑在 updateCard 中
  }

  @Test
  void testOnToolStart() {
    // Given - 必须先 start() 才能有 cardMessageId
    when(cardBuilder.buildInitialCard()).thenReturn("{\"initial\"}");
    when(sender.sendCard(anyString(), anyString(), anyString())).thenReturn("card_msg_id");
    when(cardBuilder.buildProcessingCard(anyList(), anyList(), anyList()))
        .thenReturn("{\"card\":\"processing\"}");

    listener.start();

    // When
    listener.onToolStart("read_file");

    // Then
    verify(cardBuilder).buildProcessingCard(anyList(), anyList(), anyList());
    verify(sender).updateCard("card_msg_id", "{\"card\":\"processing\"}");
  }

  @Test
  void testFinishSuccess() {
    // Given
    when(cardBuilder.buildInitialCard()).thenReturn("{\"initial\"}");
    when(sender.sendCard(anyString(), anyString(), anyString())).thenReturn("card_msg_id");
    when(cardBuilder.buildCompletedCard("Final answer")).thenReturn("{\"completed\"}");

    listener.start();

    // When
    listener.finish("Final answer", null);

    // Then
    verify(cardBuilder).buildCompletedCard("Final answer");
    verify(sender).updateCard("card_msg_id", "{\"completed\"}");
  }

  @Test
  void testFinishError() {
    // Given
    when(cardBuilder.buildInitialCard()).thenReturn("{\"initial\"}");
    when(sender.sendCard(anyString(), anyString(), anyString())).thenReturn("card_msg_id");
    when(cardBuilder.buildErrorCard("Something went wrong")).thenReturn("{\"error\"}");

    listener.start();

    // When
    listener.finish(null, "Something went wrong");

    // Then
    verify(cardBuilder).buildErrorCard("Something went wrong");
    verify(sender).updateCard("card_msg_id", "{\"error\"}");
  }
}
