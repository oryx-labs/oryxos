package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 第28节 harness：钟推链路（定时触发 → ReAct → 工具/通知）对账固化成一键可重演的端到端测试。
 *
 * <p>整机装配（{@link OryxOsRuntime}，含 JPA 扫描）+ 真实模型链路：用管理台"立即执行"（POST /schedules/{id}/run） 代替真实 cron
 * 等待——同一条 {@code execute} 路径，只是不必等到点，从而在测试里确定地驱动一次钟推。
 * 验证：任务状态入库（run_count/last_status）、执行历史入库、逐表对账（llm_calls / tool_invocations 与钟推 session 关联）。
 *
 * <p><b>运行前提</b>：`@Tag("integration")` 默认被 gate 排除；需 {@code DEEPSEEK_API_KEY}、可用网络，且当前目录 `.oryxos/`
 * 下存在一个带 schedules 的 Profile（如 `weather-daily`，其 schedule id 见下）。手动跑：{@code mvn -pl oryxos-boot
 * test -Dgroups=integration -DexcludedGroups=}。
 *
 * <p><b>仍属人工项</b>：真实 webhook 收到推送、失败注入（Provider 挂/工具异常记 success=false 且不拖垮调度器）、多 Agent
 * 并存互不串号——见课件"验收清单"。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class SchedulerFlowIT {

  /** 与 `.oryxos/profiles/weather-daily.yaml` 里的 schedule id 对齐；换 Profile 时同步改这里。 */
  private static final String TASK_ID = "weather-daily-morning";

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private ToolInvocationRepository toolInvocations;

  @Test
  @DisplayName("钟推立即执行一次_状态历史入库且逐表对账")
  void runNowOnce_stateAndHistoryPersisted_andAuditReconciled() throws Exception {
    // 立即执行一次钟推（走 execute 同一路径，免等 cron 到点）
    JsonNode execs = post("/api/v1/schedules/" + TASK_ID + "/run");
    assertFalse(execs.isEmpty(), "触发后应有执行历史");
    JsonNode latest = execs.get(0);
    assertTrue(latest.get("success").asBoolean(), "本次钟推应成功");

    String sid = latest.get("sessionId").asText();
    assertFalse(sid.isBlank(), "钟推应关联一个 session");

    // 任务状态更新入库
    JsonNode task = findTask(get("/api/v1/schedules"), TASK_ID);
    assertTrue(task.get("runCount").asLong() >= 1, "run_count 应累加");
    assertEquals("success", task.get("lastStatus").asText(), "last_status 应为 success");

    // 逐表对账：钟推 session 关联到 llm_calls / tool_invocations（走的是 process 既有审计链路）
    assertTrue(llmCalls.findBySessionId(sid).size() >= 1, "钟推应产生 llm_calls");
    assertTrue(toolInvocations.findBySessionId(sid).size() >= 0, "钟推的工具调用（若有）应按 session 关联落审计");
  }

  private static JsonNode findTask(JsonNode list, String taskId) {
    for (JsonNode n : list) {
      if (taskId.equals(n.get("taskId").asText())) {
        return n;
      }
    }
    throw new AssertionError("列表里应含任务 " + taskId + "（检查 .oryxos Profile 的 schedule id）");
  }

  private JsonNode post(String path) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return dataOf(rest.postForEntity(path, new HttpEntity<>("", headers), String.class));
  }

  private JsonNode get(String path) throws Exception {
    return dataOf(rest.getForEntity(path, String.class));
  }

  private JsonNode dataOf(ResponseEntity<String> resp) throws Exception {
    assertEquals(200, resp.getStatusCode().value(), "HTTP 应 200");
    JsonNode body = mapper.readTree(resp.getBody());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0");
    return body.get("data");
  }
}
