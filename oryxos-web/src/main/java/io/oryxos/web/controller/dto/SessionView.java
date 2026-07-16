package io.oryxos.web.controller.dto;

import io.oryxos.core.session.Message;
import java.util.List;

/** GET /sessions/{id} 视图：会话标识、绑定 Profile、对话历史（最近 ≤100 条）。 */
public record SessionView(String sessionId, String profileName, List<Message> messages) {

  public SessionView {
    messages = messages == null ? List.of() : List.copyOf(messages);
  }
}
