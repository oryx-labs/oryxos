package io.oryxos.web.controller.dto;

import java.util.List;

/** 游标补齐页。 */
public record AgentRunEventPage(List<AgentRunEventView> events, boolean hasMore, long nextAfter) {

  public AgentRunEventPage {
    events = events == null ? List.of() : List.copyOf(events);
  }
}
