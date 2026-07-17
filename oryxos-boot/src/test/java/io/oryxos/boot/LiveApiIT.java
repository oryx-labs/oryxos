package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 黑盒集成测试：打一个**已经在外面跑着**的 OryxOS 实例（默认 {@code http://localhost:8080}），不自己起服务。
 *
 * <p>用法：先 {@code bin/start.sh 8080} 起服务（需有 {@code mock} provider + 一个 {@code provider: mock} 的
 * Agent）， 再手动跑：
 *
 * <pre>{@code
 * mvn -pl oryxos-boot test -Dtest=LiveApiIT -Dsurefire.failIfNoSpecifiedTests=false
 * # 打别的实例：-Doryxos.base-url=http://host:port  换 Agent：-Doryxos.test-profile=xxx
 * }</pre>
 *
 * <p>类名以 {@code IT} 结尾——surefire 默认只跑 {@code *Test}，故它**不进常规 gate**，只在 {@code -Dtest=LiveApiIT}
 * 时显式运行。每个用例前先探活，服务没起则**跳过**（不误报失败）。用无 key 的 mock Agent 可反复重跑（每次带唯一 token 写记忆，断言查得到）。
 */
class LiveApiIT {

  private static final String BASE =
      System.getProperty("oryxos.base-url", "http://localhost:8080") + "/api/v1";
  private static final String PROFILE = System.getProperty("oryxos.test-profile", "mock-agent");

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void serviceMustBeUp() {
    assumeTrue(reachable(), "服务未在 " + BASE + " 运行——先 bin/start.sh，本用例跳过");
  }

  @Test
  @DisplayName("真实调用运行中的服务_建会话发对话再查回会话记忆工具")
  void liveService_driveConversation_thenQueryBack() throws Exception {
    // ① 健康
    assertEquals("ok", get("/health").get("status").asText());

    // ② 建会话（mock Agent，不烧 key）；每次随机 userId → 会话 id 每跑一次都不同（web:<随机>:profile）
    String userId = "live-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String sessionId =
        post("/sessions", "{\"profile\":\"" + PROFILE + "\",\"userId\":\"" + userId + "\"}")
            .get("sessionId")
            .asText();
    assertFalse(sessionId.isBlank(), "应拿到 sessionId");
    System.out.println("[LiveApiIT] 本次会话: " + sessionId);

    // ③ 发一条带唯一 token 的"记住…"，便于重跑时断言（触发 save_memory + 两轮 ReAct）
    String token = "livetest-" + System.currentTimeMillis();
    JsonNode reply =
        post("/sessions/" + sessionId + "/messages", "{\"content\":\"记住：" + token + "\"}");
    assertFalse(reply.get("reply").asText().isBlank(), "应有非空最终答复");

    // ④ 查会话历史（至少 user + assistant + tool + assistant 四条）
    assertTrue(get("/sessions/" + sessionId).get("messages").size() >= 4, "会话历史应完整");

    // ⑤ 会话列表里有它
    assertTrue(
        stream(get("/sessions")).anyMatch(n -> sessionId.equals(n.get("sessionId").asText())),
        "会话应出现在列表");

    // ⑥ 记忆查得到刚写入的 token（save_memory 真写了）
    assertTrue(get("/memory").asText().contains(token), "记忆应查得到本次写入");

    // ⑦ 工具清单含 save_memory
    assertTrue(
        stream(get("/tools")).anyMatch(n -> "save_memory".equals(n.get("name").asText())),
        "工具清单应含 save_memory");
  }

  // —— helpers ——

  private boolean reachable() {
    try {
      HttpResponse<String> r =
          http.send(
              HttpRequest.newBuilder(URI.create(BASE + "/health"))
                  .timeout(Duration.ofSeconds(2))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return r.statusCode() == 200;
    } catch (Exception e) {
      return false;
    }
  }

  private JsonNode get(String path) throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    return dataOf(resp);
  }

  private JsonNode post(String path, String json) throws Exception {
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return dataOf(resp);
  }

  private JsonNode dataOf(HttpResponse<String> resp) throws Exception {
    assertEquals(200, resp.statusCode(), "HTTP 应 200");
    JsonNode body = mapper.readTree(resp.body());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0");
    return body.get("data");
  }

  private static Stream<JsonNode> stream(JsonNode array) {
    return StreamSupport.stream(array.spliterator(), false);
  }
}
