package io.oryxos.core.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.a2a.A2aRemoteClient;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direction I: coordinator agent produces a JSON plan, then specialists run (bounded). Fan-out is
 * parallel by default (virtual threads); set parallel=false for sequential. On worker failure,
 * optional bounded replan asks the coordinator for replacement subtasks (Vision「有界迭代」). Subtasks
 * may set {@code remote} (peer base URL) to run via {@link A2aRemoteClient}. Persists when a {@link
 * TeamTaskRunStore} is provided.
 */
public final class TeamTaskOrchestrator {

  private static final Pattern JSON_BLOCK =
      Pattern.compile("\\{[\\s\\S]*\"subtasks\"[\\s\\S]*\\}", Pattern.MULTILINE);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final TeamAgentRunner runner;
  private final String defaultCoordinator;
  private final int maxSubtasks;
  private final boolean parallel;
  private final boolean replanOnFailure;
  private final int maxReplanRounds;
  private final TeamTaskRunStore runStore;
  private final TeamAgentCatalog agentCatalog;
  private final A2aRemoteClient remoteClient;

  public TeamTaskOrchestrator(TeamAgentRunner runner, String defaultCoordinator, int maxSubtasks) {
    this(runner, defaultCoordinator, maxSubtasks, true, true, 1, null, null);
  }

  public TeamTaskOrchestrator(
      TeamAgentRunner runner,
      String defaultCoordinator,
      int maxSubtasks,
      TeamTaskRunStore runStore,
      TeamAgentCatalog agentCatalog) {
    this(runner, defaultCoordinator, maxSubtasks, true, true, 1, runStore, agentCatalog);
  }

  public TeamTaskOrchestrator(
      TeamAgentRunner runner,
      String defaultCoordinator,
      int maxSubtasks,
      boolean parallel,
      TeamTaskRunStore runStore,
      TeamAgentCatalog agentCatalog) {
    this(runner, defaultCoordinator, maxSubtasks, parallel, true, 1, runStore, agentCatalog);
  }

  public TeamTaskOrchestrator(
      TeamAgentRunner runner,
      String defaultCoordinator,
      int maxSubtasks,
      boolean parallel,
      boolean replanOnFailure,
      int maxReplanRounds,
      TeamTaskRunStore runStore,
      TeamAgentCatalog agentCatalog) {
    this(
        runner,
        defaultCoordinator,
        maxSubtasks,
        parallel,
        replanOnFailure,
        maxReplanRounds,
        runStore,
        agentCatalog,
        null);
  }

  public TeamTaskOrchestrator(
      TeamAgentRunner runner,
      String defaultCoordinator,
      int maxSubtasks,
      boolean parallel,
      boolean replanOnFailure,
      int maxReplanRounds,
      TeamTaskRunStore runStore,
      TeamAgentCatalog agentCatalog,
      A2aRemoteClient remoteClient) {
    this.runner = Objects.requireNonNull(runner, "runner");
    this.defaultCoordinator =
        defaultCoordinator == null || defaultCoordinator.isBlank()
            ? "coordinator"
            : defaultCoordinator.strip();
    this.maxSubtasks = maxSubtasks <= 0 ? 4 : Math.min(maxSubtasks, 16);
    this.parallel = parallel;
    this.replanOnFailure = replanOnFailure;
    this.maxReplanRounds = maxReplanRounds <= 0 ? 0 : Math.min(maxReplanRounds, 3);
    this.runStore = runStore;
    this.agentCatalog = agentCatalog;
    this.remoteClient = remoteClient;
  }

  public TeamTaskResult run(String goal) {
    return run(goal, null);
  }

  public TeamTaskResult run(String goal, String coordinatorOverride) {
    if (goal == null || goal.isBlank()) {
      throw new IllegalArgumentException("goal must not be blank");
    }
    String taskId = UUID.randomUUID().toString();
    String coordinator =
        coordinatorOverride == null || coordinatorOverride.isBlank()
            ? defaultCoordinator
            : coordinatorOverride.strip();
    String planPrompt =
        """
        You are the team coordinator. For the user goal below, reply with ONLY a JSON object:
        {"subtasks":[{"agent":"<agent-name>","message":"<concrete subtask>","remote":"<optional-peer-base-url>"}]}
        At most MAX_SUBTASKS subtasks. Omit remote for local agents; set remote to a peer base URL
        (http://host:port) for cross-node A2A when that host is allowlisted.
        Known agents:
        KNOWN_AGENTS
        Goal:
        GOAL_TEXT
        """
            .replace("MAX_SUBTASKS", Integer.toString(maxSubtasks))
            .replace("KNOWN_AGENTS", formatKnownAgents())
            .replace("GOAL_TEXT", goal.strip());
    String planRaw = runner.run(coordinator, planPrompt);
    TeamTaskPlan plan = parsePlan(planRaw);

    List<TeamTaskResult.WorkerResult> allWorkers = new ArrayList<>();
    List<TeamTaskPlan.SubTask> work = takeBounded(plan.subtasks(), maxSubtasks);
    List<TeamTaskResult.WorkerResult> roundResults =
        parallel ? runParallel(work) : runSequential(work);
    allWorkers.addAll(roundResults);

    int rounds = 0;
    while (replanOnFailure
        && rounds < maxReplanRounds
        && roundResults.stream().anyMatch(TeamTaskResult.WorkerResult::failed)) {
      rounds++;
      List<TeamTaskResult.WorkerResult> failed =
          roundResults.stream().filter(TeamTaskResult.WorkerResult::failed).toList();
      String replanRaw = runner.run(coordinator, buildReplanPrompt(goal.strip(), failed));
      TeamTaskPlan replan;
      try {
        replan = parsePlan(replanRaw);
      } catch (IllegalStateException e) {
        break;
      }
      List<TeamTaskPlan.SubTask> replacements = takeBounded(replan.subtasks(), maxSubtasks);
      if (replacements.isEmpty()) {
        break;
      }
      roundResults = parallel ? runParallel(replacements) : runSequential(replacements);
      allWorkers.addAll(roundResults);
    }

    List<String> resultBlocks = new ArrayList<>();
    for (TeamTaskResult.WorkerResult wr : allWorkers) {
      if (wr.failed()) {
        resultBlocks.add(wr.agent() + " FAILED: " + wr.error());
      } else {
        resultBlocks.add(wr.agent() + ": " + wr.reply());
      }
    }
    String summaryPrompt =
        "Summarize the team delivery for the goal.\nGoal: "
            + goal.strip()
            + "\nWorker results:\n"
            + String.join("\n---\n", resultBlocks);
    String summary = runner.run(coordinator, summaryPrompt);

    TeamTaskResult.Builder out =
        TeamTaskResult.builder()
            .id(taskId)
            .goal(goal.strip())
            .coordinator(coordinator)
            .planRaw(planRaw)
            .summary(summary);
    for (TeamTaskResult.WorkerResult wr : allWorkers) {
      out.addWorker(wr);
    }
    TeamTaskResult result = out.build();
    if (runStore != null) {
      runStore.save(result);
    }
    return result;
  }

  public Optional<TeamTaskResult> find(String taskId) {
    if (runStore == null) {
      return Optional.empty();
    }
    return runStore.find(taskId);
  }

  private String buildReplanPrompt(String goal, List<TeamTaskResult.WorkerResult> failed) {
    StringBuilder failures = new StringBuilder();
    for (TeamTaskResult.WorkerResult f : failed) {
      failures
          .append("- agent=")
          .append(f.agent())
          .append(" message=")
          .append(f.message())
          .append(" error=")
          .append(f.error())
          .append('\n');
    }
    return """
        Some specialist subtasks failed. Reply with ONLY a JSON object of replacement subtasks
        (different agents and/or clearer messages):
        {"subtasks":[{"agent":"<agent-name>","message":"<concrete subtask>","remote":"<optional-peer-base-url>"}]}
        At most MAX_SUBTASKS subtasks. Known agents:
        KNOWN_AGENTS
        Goal:
        GOAL_TEXT
        Failures:
        FAILURES
        """
        .replace("MAX_SUBTASKS", Integer.toString(maxSubtasks))
        .replace("KNOWN_AGENTS", formatKnownAgents())
        .replace("GOAL_TEXT", goal)
        .replace("FAILURES", failures.toString().strip());
  }

  private static List<TeamTaskPlan.SubTask> takeBounded(
      List<TeamTaskPlan.SubTask> subtasks, int max) {
    List<TeamTaskPlan.SubTask> work = new ArrayList<>();
    int n = 0;
    for (TeamTaskPlan.SubTask sub : subtasks) {
      if (n >= max) {
        break;
      }
      n++;
      work.add(sub);
    }
    return work;
  }

  private String formatKnownAgents() {
    if (agentCatalog == null) {
      return "(none listed — use agent names that exist in this workspace)";
    }
    List<String> names = agentCatalog.names();
    if (names == null || names.isEmpty()) {
      return "(none listed — use agent names that exist in this workspace)";
    }
    return String.join(", ", names);
  }

  private List<TeamTaskResult.WorkerResult> runSequential(List<TeamTaskPlan.SubTask> work) {
    List<TeamTaskResult.WorkerResult> out = new ArrayList<>(work.size());
    for (TeamTaskPlan.SubTask sub : work) {
      out.add(runOne(sub));
    }
    return out;
  }

  @SuppressWarnings("PMD.ThreadPoolCreationRule")
  private List<TeamTaskResult.WorkerResult> runParallel(List<TeamTaskPlan.SubTask> work) {
    if (work.isEmpty()) {
      return List.of();
    }
    if (work.size() == 1) {
      return List.of(runOne(work.get(0)));
    }
    List<TeamTaskResult.WorkerResult> out = new ArrayList<>(work.size());
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<TeamTaskResult.WorkerResult>> futures = new ArrayList<>(work.size());
      for (TeamTaskPlan.SubTask sub : work) {
        futures.add(pool.submit(() -> runOne(sub)));
      }
      for (int i = 0; i < futures.size(); i++) {
        TeamTaskPlan.SubTask sub = work.get(i);
        try {
          out.add(futures.get(i).get());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          out.add(
              new TeamTaskResult.WorkerResult(
                  sub.agent(), sub.message(), "", "interrupted: " + e.getClass().getSimpleName()));
        } catch (ExecutionException e) {
          Throwable c = e.getCause() == null ? e : e.getCause();
          String err = c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
          out.add(new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), "", err));
        }
      }
    }
    return out;
  }

  private TeamTaskResult.WorkerResult runOne(TeamTaskPlan.SubTask sub) {
    try {
      String reply = invoke(sub);
      return new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), reply, null);
    } catch (RuntimeException e) {
      String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      return new TeamTaskResult.WorkerResult(sub.agent(), sub.message(), "", err);
    }
  }

  private String invoke(TeamTaskPlan.SubTask sub) {
    if (!sub.hasRemote()) {
      return runner.run(sub.agent(), sub.message());
    }
    if (remoteClient == null) {
      throw new IllegalStateException(
          "remote subtask requires A2A client; set oryxos.a2a.enabled=true");
    }
    try {
      return remoteClient.sendToBase(URI.create(sub.remote()), sub.agent(), sub.message());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("remote A2A interrupted", e);
    } catch (Exception e) {
      String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      throw new IllegalStateException("remote A2A failed: " + msg, e);
    }
  }

  static TeamTaskPlan parsePlan(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("coordinator returned empty plan");
    }
    String json = extractJson(raw);
    try {
      JsonNode root = MAPPER.readTree(json);
      JsonNode arr = root.get("subtasks");
      if (arr == null || !arr.isArray() || arr.isEmpty()) {
        throw new IllegalStateException("plan missing non-empty subtasks array");
      }
      List<TeamTaskPlan.SubTask> list = new ArrayList<>();
      for (JsonNode n : arr) {
        String agent = text(n, "agent");
        String message = text(n, "message");
        if (agent.isBlank()) {
          continue;
        }
        String remote = text(n, "remote");
        if (remote.isBlank()) {
          remote = text(n, "remoteBaseUrl");
        }
        list.add(new TeamTaskPlan.SubTask(agent, message, remote));
      }
      if (list.isEmpty()) {
        throw new IllegalStateException("plan subtasks had no usable agent entries");
      }
      return new TeamTaskPlan(list);
    } catch (IllegalStateException e) {
      throw e;
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "failed to parse coordinator plan JSON: " + e.getOriginalMessage(), e);
    }
  }

  private static String extractJson(String raw) {
    String t = raw.strip();
    if (t.startsWith("{")) {
      return t;
    }
    Matcher m = JSON_BLOCK.matcher(t);
    if (m.find()) {
      return m.group();
    }
    throw new IllegalStateException("coordinator reply contained no JSON plan object");
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? "" : v.asText("");
  }
}
