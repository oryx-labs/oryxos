package io.oryxos.core.task;

import java.util.List;
import java.util.Objects;

/** Parsed coordinator plan: bounded list of specialist subtasks. */
public record TeamTaskPlan(List<SubTask> subtasks) {

  public TeamTaskPlan {
    subtasks = subtasks == null ? List.of() : List.copyOf(subtasks);
  }

  /**
   * @param remote optional peer base URL for cross-node A2A ({@code a2a_send} path); blank = local
   */
  public record SubTask(String agent, String message, String remote) {
    public SubTask(String agent, String message) {
      this(agent, message, "");
    }

    public SubTask {
      agent = Objects.requireNonNull(agent, "agent").strip();
      message = message == null ? "" : message.strip();
      remote = remote == null ? "" : remote.strip().replaceAll("/+$", "");
      if (agent.isEmpty()) {
        throw new IllegalArgumentException("subtask agent must not be blank");
      }
    }

    public boolean hasRemote() {
      return !remote.isBlank();
    }
  }
}
