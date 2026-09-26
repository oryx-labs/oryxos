package io.oryxos.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.a2a.A2aRemoteClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TeamTaskOrchestratorTest {

  @Test
  @DisplayName("parsePlan extracts JSON from prose")
  void parsePlan_fromProse() {
    TeamTaskPlan plan =
        TeamTaskOrchestrator.parsePlan(
            "Sure.\n{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"draft README\"}]}\nThanks");
    assertEquals(1, plan.subtasks().size());
    assertEquals("writer", plan.subtasks().get(0).agent());
  }

  @Test
  @DisplayName("run fans out to workers then summarizes")
  void run_fansOut() {
    Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    TeamAgentRunner runner =
        (agent, msg) -> {
          calls.computeIfAbsent(agent, a -> new AtomicInteger()).incrementAndGet();
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"researcher\",\"message\":\"find facts\"},{\"agent\":\"writer\",\"message\":\"write blurb\"}]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          return "ok:" + agent + ":" + msg;
        };
    TeamTaskOrchestrator orch = new TeamTaskOrchestrator(runner, "coordinator", 4);
    TeamTaskResult result = orch.run("Ship a brief");
    assertEquals("coordinator", result.coordinator());
    assertEquals(2, result.workers().size());
    assertEquals("DONE", result.summary());
    assertTrue(calls.get("coordinator").get() >= 2);
    assertEquals(1, calls.get("researcher").get());
    assertEquals(1, calls.get("writer").get());
  }

  @Test
  @DisplayName("blank goal fails")
  void blankGoal_fails() {
    TeamTaskOrchestrator orch = new TeamTaskOrchestrator((a, m) -> "", "c", 2);
    assertThrows(IllegalArgumentException.class, () -> orch.run("  "));
  }

  @Test
  @DisplayName("maxSubtasks caps fan-out")
  void maxCaps() {
    List<String> workers = new ArrayList<>();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":["
                + "{\"agent\":\"a1\",\"message\":\"1\"},"
                + "{\"agent\":\"a2\",\"message\":\"2\"},"
                + "{\"agent\":\"a3\",\"message\":\"3\"}"
                + "]}";
          }
          if (!msg.startsWith("Summarize")) {
            workers.add(agent);
          }
          return "ok";
        };
    TeamTaskResult r = new TeamTaskOrchestrator(runner, "c", 2).run("goal");
    assertEquals(2, r.workers().size());
    assertEquals(List.of("a1", "a2"), workers);
  }

  @Test
  @DisplayName("persist assigns id and find returns it")
  void persist_and_find() {
    InMemoryTeamTaskRunStore store = new InMemoryTeamTaskRunStore();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"draft\"}]}";
          }
          return "ok";
        };
    TeamTaskOrchestrator orch =
        new TeamTaskOrchestrator(
            runner, "coordinator", 4, store, () -> java.util.List.of("writer"));
    TeamTaskResult result = orch.run("Ship");
    assertTrue(result.id() != null && !result.id().isBlank());
    assertEquals(result.id(), orch.find(result.id()).orElseThrow().id());
    assertTrue(orch.find("missing").isEmpty());
  }

  @Test
  @DisplayName("parallel fan-out overlaps specialist work")
  void parallel_overlaps() throws Exception {
    java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(2);
    java.util.concurrent.atomic.AtomicInteger maxInFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    java.util.concurrent.atomic.AtomicInteger inFlight =
        new java.util.concurrent.atomic.AtomicInteger();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"a1\",\"message\":\"1\"},{\"agent\":\"a2\",\"message\":\"2\"}]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          int now = inFlight.incrementAndGet();
          maxInFlight.updateAndGet(m -> Math.max(m, now));
          started.countDown();
          try {
            started.await(2, java.util.concurrent.TimeUnit.SECONDS);
            Thread.sleep(80);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            inFlight.decrementAndGet();
          }
          return "ok:" + agent;
        };
    TeamTaskResult r = new TeamTaskOrchestrator(runner, "c", 4, true, null, null).run("goal");
    assertEquals(2, r.workers().size());
    assertEquals("a1", r.workers().get(0).agent());
    assertEquals("a2", r.workers().get(1).agent());
    assertTrue(
        maxInFlight.get() >= 2,
        "expected overlapping specialists, maxInFlight=" + maxInFlight.get());
  }

  @Test
  @DisplayName("replan runs replacement after specialist failure")
  void replan_onFailure() {
    AtomicInteger coordPlans = new AtomicInteger();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON") && msg.contains("team coordinator")) {
            coordPlans.incrementAndGet();
            return "{\"subtasks\":[{\"agent\":\"flaky\",\"message\":\"do work\"}]}";
          }
          if (msg.contains("Some specialist subtasks failed")) {
            coordPlans.incrementAndGet();
            return "{\"subtasks\":[{\"agent\":\"backup\",\"message\":\"retry work\"}]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          if ("flaky".equals(agent)) {
            throw new IllegalStateException("boom");
          }
          return "ok:" + agent;
        };
    TeamTaskResult r =
        new TeamTaskOrchestrator(runner, "c", 4, true, true, 1, null, null).run("goal");
    assertEquals(2, r.workers().size());
    assertTrue(r.workers().get(0).failed());
    assertEquals("flaky", r.workers().get(0).agent());
    assertFalse(r.workers().get(1).failed());
    assertEquals("backup", r.workers().get(1).agent());
    assertEquals("DONE", r.summary());
    assertTrue(coordPlans.get() >= 2);
  }

  @Test
  @DisplayName("replanOnFailure=false keeps failed workers only")
  void replan_disabled() {
    AtomicInteger replanCalls = new AtomicInteger();
    TeamAgentRunner runner =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"flaky\",\"message\":\"do work\"}]}";
          }
          if (msg.contains("Some specialist subtasks failed")) {
            replanCalls.incrementAndGet();
            return "{\"subtasks\":[{\"agent\":\"backup\",\"message\":\"retry\"}]}";
          }
          if (msg.startsWith("Summarize")) {
            return "DONE";
          }
          throw new IllegalStateException("boom");
        };
    TeamTaskResult r =
        new TeamTaskOrchestrator(runner, "c", 4, true, false, 1, null, null).run("goal");
    assertEquals(1, r.workers().size());
    assertTrue(r.workers().get(0).failed());
    assertEquals(0, replanCalls.get());
  }

  @Test
  @DisplayName("parsePlan reads optional remote")
  void parsePlan_remote() {
    TeamTaskPlan plan =
        TeamTaskOrchestrator.parsePlan(
            "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"draft\",\"remote\":\"http://peer:8080/\"}]}");
    assertEquals("http://peer:8080", plan.subtasks().get(0).remote());
    assertTrue(plan.subtasks().get(0).hasRemote());
  }

  @Test
  @DisplayName("remote subtask uses A2aRemoteClient")
  void remote_subtask() throws Exception {
    com.sun.net.httpserver.HttpServer server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    String origin = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/.well-known/agent-card.json",
        ex -> {
          byte[] body =
              ("{\"protocolVersion\":\"0.3.0\",\"name\":\"P\",\"description\":\"t\",\"url\":\""
                      + origin
                      + "/api/v1/a2a\",\"preferredTransport\":\"JSONRPC\",\"version\":\"1\","
                      + "\"capabilities\":{\"streaming\":false,\"pushNotifications\":false,"
                      + "\"stateTransitionHistory\":false},\"defaultInputModes\":[\"text/plain\"],"
                      + "\"defaultOutputModes\":[\"text/plain\"],\"skills\":[]}")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (java.io.OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/api/v1/a2a",
        ex -> {
          byte[] body =
              "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"message\",\"role\":\"agent\","
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          // build full body
          body =
              ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"kind\":\"message\",\"role\":\"agent\","
                      + "\"messageId\":\"m\",\"parts\":[{\"kind\":\"text\",\"text\":\"remote-ok\"}]}}")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, body.length);
          try (java.io.OutputStream out = ex.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      A2aRemoteClient remote =
          A2aRemoteClient.create(
              java.time.Duration.ofSeconds(5), uri -> "127.0.0.1".equals(uri.getHost()));
      String remoteJson =
          "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"hi\",\"remote\":\""
              + origin
              + "\"}]}";
      TeamAgentRunner local =
          (agent, msg) -> {
            if (msg.contains("ONLY a JSON")) {
              return remoteJson;
            }
            if (msg.startsWith("Summarize")) {
              return "SUM";
            }
            return "local";
          };
      TeamTaskOrchestrator orch =
          new TeamTaskOrchestrator(local, "c", 4, true, false, 0, null, null, remote);
      TeamTaskResult r = orch.run("goal");
      assertEquals("remote-ok", r.workers().get(0).reply());
      assertFalse(r.workers().get(0).failed());
    } finally {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("remote without client fails worker")
  void remote_without_client() {
    TeamAgentRunner local =
        (agent, msg) -> {
          if (msg.contains("ONLY a JSON")) {
            return "{\"subtasks\":[{\"agent\":\"writer\",\"message\":\"hi\",\"remote\":\"http://peer\"}]}";
          }
          return "SUM";
        };
    TeamTaskResult r =
        new TeamTaskOrchestrator(local, "c", 4, true, false, 0, null, null, null).run("g");
    assertTrue(r.workers().get(0).failed());
    assertTrue(r.workers().get(0).error().contains("A2A client"));
  }
}
