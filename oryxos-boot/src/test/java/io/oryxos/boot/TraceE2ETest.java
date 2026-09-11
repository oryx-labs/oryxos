package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCall;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 021 端到端：mock provider（第一轮固定调 save_memory、第二轮收尾 = 每轮 2 次 LLM + 1 次工具）+ 真实 HTTP + SQLite——
 * 单轮全链路串联可回放（SC-002）、连续两轮各自成链、并发处理互不串号（SC-003）。无 key、无网络、gate 内可跑。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TraceE2ETest {

  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private ToolInvocationRepository toolInvocations;

  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-trace-e2e");
      Files.createDirectories(root.resolve("memory"));
      for (String agent : new String[] {"agent-a", "agent-b"}) {
        Files.createDirectories(root.resolve("agents").resolve(agent));
        Files.writeString(
            root.resolve("agents/" + agent + "/AGENT.md"),
            """
            ---
            name: %s
            description: trace 走查 Agent
            identity:
              agent_name: 小欧
              prompt: 你是一个测试助手。
            provider:
              name: mock
              model: mock-model
            tools:
              - save_memory
              - recall_memory
            settings:
              max_iterations: 10
              max_history_turns: 20
            ---
            你是一个测试助手，被触发时正常回应。
            """
                .formatted(agent));
      }
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("trace-e2e.db"));
  }

  @Test
  @Order(1)
  void 单轮全链路_审计同traceId_时间线可回放() {
    long maxIdBefore = maxLlmId();
    invoke("agent-a", "记住我喜欢咖啡");

    // 本轮全部审计记录共享同一 traceId（SC-002 串联完整性）
    List<LlmCall> round = llmCallsAfter(maxIdBefore);
    assertEquals(2, round.size(), "mock 每轮固定 2 次 LLM 调用");
    String traceId = round.get(0).getTraceId();
    assertNotNull(traceId, "落库必须携带 traceId");
    assertTrue(round.stream().allMatch(c -> traceId.equals(c.getTraceId())));
    assertTrue(
        toolInvocations.findByTraceId(traceId).stream()
            .anyMatch(t -> "save_memory".equals(t.getToolName())),
        "本轮工具调用同链");

    // 时间线 API 回放：思考→调工具→再思考
    JsonNode data = timeline(traceId);
    assertTrue(data.get("found").asBoolean());
    JsonNode steps = data.get("steps");
    assertEquals(3, steps.size());
    assertEquals("LLM", steps.get(0).get("type").asText());
    assertEquals("TOOL", steps.get(1).get("type").asText());
    assertEquals("LLM", steps.get(2).get("type").asText());
    assertEquals("save_memory", steps.get(1).get("name").asText());
    JsonNode summary = data.get("summary");
    assertEquals(3, summary.get("steps").asInt());
    assertEquals(2, summary.get("llmCalls").asInt());
    assertEquals(1, summary.get("toolCalls").asInt());
    assertTrue(summary.get("totalDurationMs").asLong() >= 0);
  }

  @Test
  @Order(2)
  void 连续两轮_各自traceId互不混串() {
    long beforeFirst = maxLlmId();
    invoke("agent-a", "第一轮");
    long beforeSecond = maxLlmId();
    invoke("agent-a", "第二轮");

    String firstTrace = llmCallsAfter(beforeFirst).get(0).getTraceId();
    String secondTrace = llmCallsAfter(beforeSecond).get(0).getTraceId();
    assertNotEquals(firstTrace, secondTrace, "同 Agent 两轮各有各的 trace");
    // 各查各的：每条链恰好是自己那轮的 3 步
    assertEquals(3, timeline(firstTrace).get("steps").size());
    assertEquals(3, timeline(secondTrace).get("steps").size());
  }

  @Test
  @Order(3)
  void 并发两Agent同时invoke_审计无串号() throws Exception {
    long maxIdBefore = maxLlmId();

    Thread a = Thread.ofVirtual().start(() -> invoke("agent-a", "并发场景A"));
    Thread b = Thread.ofVirtual().start(() -> invoke("agent-b", "并发场景B"));
    a.join();
    b.join();

    // 新增 LLM 记录按 trace 分组：恰 2 条链、每链 2 次调用且会话标识内聚（SC-003 并发隔离）
    Map<String, List<LlmCall>> byTrace =
        llmCallsAfter(maxIdBefore).stream().collect(Collectors.groupingBy(LlmCall::getTraceId));
    assertEquals(2, byTrace.size(), "两次并发处理应各成一链");
    for (List<LlmCall> chain : byTrace.values()) {
      assertEquals(2, chain.size(), "每链恰好本轮的 2 次 LLM 调用");
      Set<String> sessions = chain.stream().map(LlmCall::getSessionId).collect(Collectors.toSet());
      assertEquals(1, sessions.size(), "同链会话标识必须内聚——串号即失败");
    }
  }

  @Test
  @Order(4)
  void REST非流式响应traceId_与审计一致() {
    long maxIdBefore = maxLlmId();
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/agent-a/invoke",
            new HttpEntity<>("{\"content\":\"REST回传场景\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    JsonNode data = readJson(response.getBody()).get("data");
    String returnedTrace = data.get("traceId").asText();
    assertFalse(returnedTrace.isBlank(), "响应体必须带 traceId（SC-004）");
    // 回传的 ID 与本轮审计落库同值——报障凭它可精准定位
    assertTrue(
        llmCallsAfter(maxIdBefore).stream().allMatch(c -> returnedTrace.equals(c.getTraceId())));
    assertTrue(timeline(returnedTrace).get("found").asBoolean());
  }

  @Test
  @Order(5)
  void SSE流首trace事件_done同带_与审计一致() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("Accept", "text/event-stream");
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/agent-a/invoke",
            new HttpEntity<>("{\"content\":\"SSE回传场景\"}", headers),
            String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());

    String body = response.getBody();
    assertNotNull(body);
    int traceEventAt = body.indexOf("event: trace");
    assertTrue(traceEventAt >= 0, "流内必须有 trace 事件");
    assertTrue(traceEventAt < body.indexOf("event: done"), "trace 事件必须先于 done（首个业务事件）");
    String traceId =
        body.substring(traceEventAt).replaceAll("(?s).*?\"traceId\":\"([^\"]+)\".*", "$1");
    assertTrue(
        body.substring(body.indexOf("event: done")).contains("\"traceId\":\"" + traceId + "\""),
        "done 负载同带同一 traceId");
    assertTrue(timeline(traceId).get("found").asBoolean(), "用流内 ID 查审计必命中本轮");
  }

  @Test
  @Order(6)
  void 定时触发执行记录带traceId_后台线程传递正确_日志MDC同值() throws Exception {
    var root =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    root.addAppender(appender);
    try {
      HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      ResponseEntity<String> triggered =
          rest.postForEntity(
              "/api/v1/agents/agent-a/trigger",
              new HttpEntity<>("{\"content\":\"触发场景\"}", headers),
              String.class);
      assertEquals(HttpStatus.OK, triggered.getStatusCode());
      long executionId = readJson(triggered.getBody()).get("data").get("executionId").asLong();

      JsonNode execution = awaitExecutionDone(executionId);
      String traceId = execution.get("traceId").asText();
      assertFalse(traceId.isBlank(), "执行记录必须带 traceId（US2 场景 3）");

      // 后台虚拟线程显式传递正确：该 trace 命中本轮完整审计链（R4 唯一跨线程点）
      JsonNode data = timeline(traceId);
      assertTrue(data.get("found").asBoolean());
      assertEquals(2, data.get("summary").get("llmCalls").asInt());

      // 日志与审计互查（SC-007）：后台执行的日志行 MDC 携带同一 traceId
      assertTrue(
          appender.list.stream()
              .anyMatch(e -> traceId.equals(e.getMDCPropertyMap().get("traceId"))),
          "本轮关键日志行应携带同一 traceId（MDC 贯穿）");
    } finally {
      root.detachAppender(appender);
    }
  }

  @Test
  @Order(7)
  void 脱敏_展示层掩码_落库原文完整_双向断言() {
    long maxIdBefore = maxLlmId();
    // mock 会把消息原文作为 save_memory 的 content 参数 → 敏感形态进入 tool_invocations.input_json
    invoke("agent-a", "配置 \"password\":\"p@ss123\" 与凭证 Bearer sk-abcdefgh12345678");
    String traceId = llmCallsAfter(maxIdBefore).get(0).getTraceId();

    // 展示层：时间线 TOOL 步摘要必须掩码（SC-006）
    JsonNode data = timeline(traceId);
    String inputSummary = null;
    for (JsonNode step : data.get("steps")) {
      if ("TOOL".equals(step.get("type").asText())) {
        inputSummary = step.get("inputSummary").asText();
      }
    }
    assertNotNull(inputSummary, "时间线应含 TOOL 步摘要");
    assertTrue(inputSummary.contains("p@ss****"), "password 值应掩码: " + inputSummary);
    assertTrue(inputSummary.contains("sk-a****"), "API key 应掩码: " + inputSummary);
    assertFalse(inputSummary.contains("p@ss123"), "展示层不得出现原文口令");
    assertFalse(inputSummary.contains("sk-abcdefgh12345678"), "展示层不得出现原文 key");

    // 库侧：原文完整（排障现场，Clarifications 裁决）
    var stored =
        toolInvocations.findByTraceId(traceId).stream()
            .filter(t -> "save_memory".equals(t.getToolName()))
            .findFirst()
            .orElseThrow();
    assertTrue(stored.getInputJson().contains("p@ss123"), "库中口令原文必须完整");
    assertTrue(stored.getInputJson().contains("sk-abcdefgh12345678"), "库中 key 原文必须完整");
  }

  // —— helpers ——

  private JsonNode awaitExecutionDone(long executionId) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      ResponseEntity<String> response =
          rest.getForEntity("/api/v1/agents/agent-a/executions", String.class);
      assertEquals(HttpStatus.OK, response.getStatusCode());
      for (JsonNode row : readJson(response.getBody()).get("data")) {
        if (row.get("id").asLong() != executionId) {
          continue;
        }
        // QUEUED / RUNNING / CANCELLING 都是进行中——不能把非 RUNNING 当成终态
        // （否则 trigger 刚落库为 QUEUED 时会立刻 assert SUCCESS 失败）
        String status = row.get("status").asText();
        if ("SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
          assertEquals("SUCCESS", status);
          return row;
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("后台执行 10 秒内未完成（executionId=" + executionId + "）");
  }

  private long maxLlmId() {
    return llmCalls.findAll().stream().mapToLong(LlmCall::getId).max().orElse(0);
  }

  private List<LlmCall> llmCallsAfter(long maxIdBefore) {
    List<LlmCall> rows = llmCalls.findAll().stream().filter(c -> c.getId() > maxIdBefore).toList();
    assertFalse(rows.isEmpty(), "本轮应产生新的 LLM 审计记录");
    return rows;
  }

  private JsonNode timeline(String traceId) {
    ResponseEntity<String> response =
        rest.getForEntity("/api/v1/audit/trace/" + traceId, String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return readJson(response.getBody()).get("data");
  }

  private String invoke(String agent, String content) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    // 消息可能含引号等 JSON 特殊字符（脱敏用例）——必须序列化而非手拼
    String body = mapper.createObjectNode().put("content", content).toString();
    ResponseEntity<String> response =
        rest.postForEntity(
            "/api/v1/agents/" + agent + "/invoke", new HttpEntity<>(body, headers), String.class);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return readJson(response.getBody()).get("data").get("reply").asText();
  }

  private JsonNode readJson(String raw) {
    try {
      return mapper.readTree(raw);
    } catch (IOException e) {
      throw new IllegalStateException("invalid json: " + raw, e);
    }
  }
}
