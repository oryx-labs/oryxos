package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamTaskWaveScheduleTest {

  @Test
  @DisplayName("parsePlan reads after / dependsOn")
  void parseAfter() {
    TeamTaskPlan plan =
        TeamTaskOrchestrator.parsePlan(
            "{\"subtasks\":["
                + "{\"agent\":\"research\",\"message\":\"r\"},"
                + "{\"agent\":\"writer\",\"message\":\"w\",\"after\":[\"research\"]},"
                + "{\"agent\":\"editor\",\"message\":\"e\",\"dependsOn\":\"writer\"}"
                + "]}");
    assertEquals(List.of(), plan.subtasks().get(0).after());
    assertEquals(List.of("research"), plan.subtasks().get(1).after());
    assertEquals(List.of("writer"), plan.subtasks().get(2).after());
  }

  @Test
  @DisplayName("scheduleWaves orders by after")
  void waves() {
    List<TeamTaskPlan.SubTask> work =
        List.of(
            new TeamTaskPlan.SubTask("a", "1"),
            new TeamTaskPlan.SubTask("b", "2", "", List.of("a")),
            new TeamTaskPlan.SubTask("c", "3", "", List.of("a")));
    List<List<TeamTaskPlan.SubTask>> waves = TeamTaskOrchestrator.scheduleWaves(work);
    assertEquals(2, waves.size());
    assertEquals(List.of("a"), waves.get(0).stream().map(TeamTaskPlan.SubTask::agent).toList());
    assertEquals(2, waves.get(1).size());
  }

  @Test
  @DisplayName("scheduleWaves waits for every subtask on an agent named twice")
  void wavesWithDuplicateAgent() {
    // `after` names agents, so a plan may reuse one. The first subtask on "a" entering wave0 must
    // not satisfy `after:["a"]` while a second subtask on "a" is still queued.
    List<TeamTaskPlan.SubTask> work =
        List.of(
            new TeamTaskPlan.SubTask("a", "first-a"),
            new TeamTaskPlan.SubTask("a", "second-a", "", List.of("b")),
            new TeamTaskPlan.SubTask("b", "bee"),
            new TeamTaskPlan.SubTask("c", "see", "", List.of("a")));
    List<List<TeamTaskPlan.SubTask>> waves = TeamTaskOrchestrator.scheduleWaves(work);
    assertEquals(3, waves.size(), waves.toString());
    assertEquals(List.of("first-a", "bee"), messages(waves.get(0)));
    assertEquals(List.of("second-a"), messages(waves.get(1)));
    assertEquals(List.of("see"), messages(waves.get(2)));
  }

  private static List<String> messages(List<TeamTaskPlan.SubTask> wave) {
    return wave.stream().map(TeamTaskPlan.SubTask::message).toList();
  }

  @Test
  @DisplayName("run respects after: writer waits for researcher")
  void run_afterDependency() throws Exception {
    ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
    CountDownLatch researchStarted = new CountDownLatch(1);
    CountDownLatch researchRelease = new CountDownLatch(1);
    AtomicInteger writerBeforeResearchDone = new AtomicInteger();

    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":["
                + "{\"agent\":\"researcher\",\"message\":\"find\"},"
                + "{\"agent\":\"writer\",\"message\":\"write\",\"after\":[\"researcher\"]}"
                + "]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          if ("researcher".equals(agent)) {
            order.add("researcher-start");
            researchStarted.countDown();
            try {
              assertTrue(researchRelease.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(e);
            }
            order.add("researcher-end");
            return "facts";
          }
          if ("writer".equals(agent)) {
            if (researchStarted.getCount() == 0 && researchRelease.getCount() > 0) {
              writerBeforeResearchDone.incrementAndGet();
            }
            order.add("writer");
            // unblock researcher if somehow writer ran first (should not)
            researchRelease.countDown();
            return "draft";
          }
          return "ok";
        };

    TeamTaskOrchestrator orch = new TeamTaskOrchestrator(runner, "c", 4);
    // Hold researcher until we assert writer has not jumped the wave.
    Thread releaser =
        new Thread(
            () -> {
              try {
                assertTrue(researchStarted.await(3, TimeUnit.SECONDS));
                // Give writer a chance to race incorrectly
                Thread.sleep(80);
                assertEquals(0, writerBeforeResearchDone.get());
                researchRelease.countDown();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    releaser.start();
    TeamTaskResult result = orch.run("Ship");
    releaser.join(5000);
    assertEquals(2, result.workers().size());
    List<String> seq = new ArrayList<>(order);
    assertTrue(seq.indexOf("researcher-end") < seq.indexOf("writer"), seq.toString());
  }
}
