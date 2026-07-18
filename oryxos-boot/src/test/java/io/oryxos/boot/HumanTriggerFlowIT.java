package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocation;
import io.oryxos.storage.ToolInvocationRepository;
import java.util.List;
import java.util.stream.StreamSupport;
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
 * 第27节 harness：把"人推链路"对账固化成一键可重演的端到端测试。
 *
 * <p>整机装配（{@link OryxOsRuntime}，含 JPA 扫描）+ 真实模型链路，覆盖三根支柱：① 对话答得出 + 逐表对账不多不少；② 记忆写得进查得到；③ 工具清单对外可见。
 *
 * <p><b>运行前提（都不满足则自动跳过/失败，属预期）</b>：`@Tag("integration")` 默认被 gate 排除；需 {@code DEEPSEEK_API_KEY}
 * 环境变量、可用网络（DeepSeek + 天气源）、以及当前目录 `.oryxos/` 下存在 `default` Profile。手动跑：{@code mvn -pl oryxos-boot
 * test -Dgroups=integration -DexcludedGroups=}。
 *
 * <p><b>仍属人工项</b>：失败路径（Provider 挂 / Sandbox 拦 / 工具异常）记 success=false、CLI 与 REST
 * 同源——需人工注入故障与切入口，见课件"验收清单"。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class HumanTriggerFlowIT {

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private ToolInvocationRepository toolInvocations;

  @Test
  @DisplayName("支柱一_对话答得出且逐表对账不多不少")
  void pillarOne_conversationAndAudit() throws Exception {
    String sid = post("/api/v1/sessions", "{\"profile\":\"default\"}").get("sessionId").asText();

    JsonNode reply =
        post("/api/v1/sessions/" + sid + "/messages", "{\"content\":\"今天北京天气怎么样，穿什么合适\"}");
    assertFalse(reply.get("reply").asText().isBlank(), "最终答复应非空");

    // 历史：用户消息 + 两次模型回复 + 一次工具结果
    JsonNode history = get("/api/v1/sessions/" + sid);
    assertTrue(history.get("messages").size() >= 3, "历史应含完整往来");

    // 三面同源之一：会话出现在列表里
    JsonNode list = get("/api/v1/sessions");
    assertTrue(
        stream(list).anyMatch(n -> sid.equals(n.get("sessionId").asText())),
        "会话应出现在 GET /sessions 列表");

    // 逐表对账：llm_calls 恰 2 条、tool_invocations 恰 1 条（http_get）、都成功——不多不少
    assertEquals(2, llmCalls.findBySessionId(sid).size(), "llm_calls 应恰 2 条");
    List<ToolInvocation> tools = toolInvocations.findBySessionId(sid);
    assertEquals(1, tools.size(), "tool_invocations 应恰 1 条");
    assertEquals("http_get", tools.get(0).getToolName());
    assertNull(tools.get(0).getErrorMessage(), "成功调用不应有 error_message");
  }

  @Test
  @DisplayName("支柱二_记忆写得进查得到")
  void pillarTwo_memoryWrittenAndReadable() throws Exception {
    String sid = post("/api/v1/sessions", "{\"profile\":\"default\"}").get("sessionId").asText();
    post("/api/v1/sessions/" + sid + "/messages", "{\"content\":\"记住：我在北京，怕冷\"}");

    String memory = get("/api/v1/agents/default/memory").asText();
    assertTrue(memory.contains("北京"), "GET /agents/default/memory 应查得到刚写入的事实");
  }

  @Test
  @DisplayName("支柱三_工具清单对外可见")
  void pillarThree_toolsVisible() throws Exception {
    JsonNode tools = get("/api/v1/tools");
    assertTrue(tools.size() > 0, "工具清单数量应大于 0");
    assertTrue(
        stream(tools).anyMatch(n -> "http_get".equals(n.get("name").asText())), "工具清单应含 http_get");
  }

  // —— helpers ——

  private JsonNode post(String path, String json) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return dataOf(rest.postForEntity(path, new HttpEntity<>(json, headers), String.class));
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

  private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false);
  }
}
