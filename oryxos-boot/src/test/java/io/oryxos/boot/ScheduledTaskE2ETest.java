package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 定时任务子系统端到端（28 节，mock provider，gate 内可跑）：启动整台服务，验证——
 *
 * <ul>
 *   <li>启动即把 Profile 里的 schedule 登记进 SQLite（GET /schedules 查得到，初始 enabled、run_count=0）；
 *   <li>POST /schedules/{id}/run 手动触发一次 → run_count=1、last_status=success，执行历史查得到；
 *   <li>触发确实走了真实 ReAct（save_memory 把消息写进 MEMORY.md）；
 *   <li>PUT /schedules/{id} 停用后 GET 列表 enabled=false。
 * </ul>
 *
 * <p>cron 设为每年 1 月 1 日 0 点，测试窗口内绝不自然触发——执行只由 POST /run 显式驱动，断言确定。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class ScheduledTaskE2ETest {

  private static final Path ROOT = seedWorkspace();
  private static final String TASK_ID = "daily-inspect";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;

  private AuthenticatedRestClient adminClient;

  /** 临时工作区：一个 provider: mock 且带一条 schedule 的 Agent；并把 oryxos.root 指过来。 */
  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-sched-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("sched-agent"));
      Files.writeString(
          root.resolve("agents/sched-agent/AGENT.md"),
          """
          ---
          name: sched-agent
          description: 定时任务自测 Agent
          identity:
            agent_name: 定时小欧
            prompt: 你是一个定时巡检助手。
          provider:
            name: mock
            model: mock-model
          tools:
            - save_memory
            - recall_memory
          schedules:
            - id: daily-inspect
              cron: "0 0 0 1 1 *"
              zone: Asia/Shanghai
              message: 记录一次定时任务巡检
          settings:
            max_iterations: 10
            max_history_turns: 20
          ---
          你是一个定时巡检助手，被触发时记录一次巡检。
          """);
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("sched-e2e.db"));
    registry.add("oryxos.admin.username", () -> "admin");
    registry.add("oryxos.admin.password-hash", () -> "{noop}secret");
    registry.add("oryxos.admin.secure-cookie", () -> "false");
  }

  @Test
  @DisplayName("启动登记任务_手动触发一次_run_count与执行历史与记忆都对得上_停用生效")
  void bootRegistersTask_runOnce_thenStateHistoryMemoryAllMatch_disableTakesEffect()
      throws Exception {
    // ① 启动即登记：列表里查得到该任务，初始启用、还没跑过
    JsonNode before = findTask(getData("/api/v1/schedules"), TASK_ID);
    assertTrue(before.get("enabled").asBoolean(), "新任务应默认启用");
    assertEquals(0, before.get("runCount").asLong(), "还没触发过，run_count 应为 0");

    // ② 立即执行一次（手动触发一次真实 ReAct）
    JsonNode execs = postData("/api/v1/schedules/" + TASK_ID + "/run");
    assertFalse(execs.isEmpty(), "触发后应有一条执行历史");
    assertTrue(execs.get(0).get("success").asBoolean(), "本次执行应成功");

    // ③ 任务状态更新：run_count=1、last_status=success
    JsonNode after = findTask(getData("/api/v1/schedules"), TASK_ID);
    assertEquals(1, after.get("runCount").asLong(), "触发一次后 run_count 应为 1");
    assertEquals("success", after.get("lastStatus").asText(), "last_status 应为 success");

    // ④ 执行历史端点查得到（成功、带 sessionId）
    JsonNode history = getData("/api/v1/schedules/" + TASK_ID + "/executions");
    assertTrue(history.size() >= 1, "执行历史应查得到");
    assertFalse(history.get(0).get("sessionId").asText().isBlank(), "执行应关联到一个 session");

    // ⑤ 确实走了真实 ReAct：save_memory 把巡检消息写进了 MEMORY.md
    assertTrue(
        getData("/api/v1/agents/sched-agent/memory").asText().contains("巡检"),
        "定时任务应真的驱动了 save_memory");

    // ⑥ 停用后列表里 enabled=false
    JsonNode afterDisable =
        findTask(putData("/api/v1/schedules/" + TASK_ID, "{\"enabled\":false}"), TASK_ID);
    assertFalse(afterDisable.get("enabled").asBoolean(), "停用后 enabled 应为 false");
  }

  private static JsonNode findTask(JsonNode list, String taskId) {
    return stream(list)
        .filter(n -> taskId.equals(n.get("taskId").asText()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("列表里应含任务 " + taskId));
  }

  private JsonNode postData(String path) throws Exception {
    return adminClient().postEmpty(path);
  }

  private JsonNode putData(String path, String json) throws Exception {
    return adminClient().putJson(path, json);
  }

  private JsonNode getData(String path) throws Exception {
    return adminClient().getData(path);
  }

  private AuthenticatedRestClient adminClient() {
    if (adminClient == null) {
      adminClient = new AuthenticatedRestClient(rest, mapper);
    }
    return adminClient;
  }

  private JsonNode dataOf(ResponseEntity<String> resp) throws Exception {
    assertEquals(200, resp.getStatusCode().value(), "HTTP 应 200");
    JsonNode body = mapper.readTree(resp.getBody());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0");
    return body.get("data");
  }

  private static Stream<JsonNode> stream(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false);
  }
}
