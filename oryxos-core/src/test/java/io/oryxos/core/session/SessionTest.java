package io.oryxos.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.core.provider.ProviderResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SessionTest {

  @Test
  @DisplayName("持久历史按用户轮次截断且不撕裂轮内消息")
  void retainingRecentTurnsKeepsWholeLatestTurns() {
    Session session = new Session("s-1", "ops-agent");
    for (int turn = 1; turn <= 3; turn++) {
      session.appendUser("问题" + turn);
      session.appendAssistant(new ProviderResponse("回答" + turn, List.of(), null));
    }

    session.retainRecentTurns(2);

    assertEquals(
        List.of("问题2", "回答2", "问题3", "回答3"),
        session.messages().stream().map(Message::content).toList());
  }
}
