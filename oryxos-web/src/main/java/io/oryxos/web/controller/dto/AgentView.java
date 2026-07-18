package io.oryxos.web.controller.dto;

import io.oryxos.core.profile.Profile;
import java.util.List;

/** GET /agents 视图：从 Profile 投影出可对外展示的字段（第 30 节）。 */
public record AgentView(
    String name,
    String description,
    String provider,
    String model,
    List<String> tools,
    List<ScheduleView> schedules) {

  public AgentView {
    tools = tools == null ? List.of() : List.copyOf(tools);
    schedules = schedules == null ? List.of() : List.copyOf(schedules);
  }

  public static AgentView from(Profile p) {
    Profile.ProviderRef pr = p.provider();
    List<ScheduleView> scheds =
        p.schedules().stream()
            .map(s -> new ScheduleView(s.id(), s.cron(), s.zone(), s.message()))
            .toList();
    return new AgentView(
        p.name(),
        p.description(),
        pr == null ? null : pr.name(),
        pr == null ? null : pr.model(),
        p.tools(),
        scheds);
  }

  public record ScheduleView(String id, String cron, String zone, String message) {}
}
