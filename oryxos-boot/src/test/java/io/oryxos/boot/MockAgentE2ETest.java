package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.storage.LlmCallRepository;
import io.oryxos.storage.ToolInvocationRepository;
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
 * 真·端到端：用 mock provider 启动整台服务（真实 HTTP 端口 + SQLite + ReAct + Memory + 审计）， 从测试里自己发起对话，跑完后把会话 / 记忆 /
 * 工具调用都查回来。无 key、无网络、gate 内可跑。
 *
 * <p>三处覆盖让它 hermetic：① 工作区指向临时目录（系统属性 {@code oryxos.root}，静态块里在类加载即设定， 早于 Spring 上下文构建）并预置一个 {@code
 * provider: mock} 的 Agent；② provider 用内置 mock（覆盖默认 deepseek）； ③ SQLite 指向临时库文件。
 */
@SpringBootTest(
    classes = OryxOsRuntime.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"oryxos.providers[0].name=mock"})
class MockAgentE2ETest {

  private static final Path ROOT = seedWorkspace();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired private TestRestTemplate rest;
  @Autowired private LlmCallRepository llmCalls;
  @Autowired private ToolInvocationRepository toolInvocations;

  private AuthenticatedRestClient adminClient;

  /** 临时工作区：一个 provider: mock 的 Agent + 空 memory 目录；并把 oryxos.root 指过来。 */
  private static Path seedWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-e2e");
      Files.createDirectories(root.resolve("memory"));
      Files.createDirectories(root.resolve("agents").resolve("mock-agent"));
      Files.writeString(
          root.resolve("agents/mock-agent/AGENT.md"),
          """
          ---
          name: mock-agent
          description: mock 自测 Agent
          identity:
            agent_name: mock小欧
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
          """);
      System.setProperty("oryxos.root", root.toString());
      return root;
    } catch (IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("e2e.db"));
    registry.add("oryxos.admin.username", () -> "admin");
    registry.add("oryxos.admin.password-hash", () -> "{noop}secret");
    registry.add("oryxos.admin.secure-cookie", () -> "false");
  }

  @Test
  @DisplayName("启动整台服务_自己发起对话_再把会话记忆工具都查回来")
  void bootRealService_driveConversation_thenQueryEverythingBack() throws Exception {
    // ① 创建会话
    String sessionId =
        postData("/api/v1/sessions", "{\"profile\":\"mock-agent\"}").get("sessionId").asText();
    assertFalse(sessionId.isBlank(), "应拿到 sessionId");

    // ② 发起一次对话（mock 会驱动一次 save_memory 工具调用 + 两轮 ReAct）
    JsonNode reply =
        postData("/api/v1/sessions/" + sessionId + "/messages", "{\"content\":\"记住：我在北京，怕冷\"}");
    assertFalse(reply.get("reply").asText().isBlank(), "应有非空最终答复");

    // ③ 查会话历史：user + 两轮 assistant + 一次 tool 结果
    JsonNode history = getData("/api/v1/sessions/" + sessionId);
    assertTrue(history.get("messages").size() >= 4, "会话历史应完整");

    // ④ 会话列表里能查到这条会话
    JsonNode list = getData("/api/v1/sessions");
    assertTrue(
        stream(list).anyMatch(n -> sessionId.equals(n.get("sessionId").asText())), "会话应出现在列表");

    // ⑤ 记忆查得到（save_memory 真写了 MEMORY.md）
    assertTrue(getData("/api/v1/agents/mock-agent/memory").asText().contains("北京"), "记忆应查得到");

    // ⑥ 工具清单查得到 save_memory
    assertTrue(
        stream(getData("/api/v1/tools"))
            .anyMatch(n -> "save_memory".equals(n.get("name").asText())),
        "工具清单应含 save_memory");

    // ⑦ 审计查得到：llm_calls 恰 2、tool_invocations 恰 1（都按 sessionId 关联）
    assertEquals(2, llmCalls.findBySessionId(sessionId).size(), "llm_calls 应恰 2 条");
    assertEquals(1, toolInvocations.findBySessionId(sessionId).size(), "tool_invocations 应恰 1 条");
    assertEquals("save_memory", toolInvocations.findBySessionId(sessionId).get(0).getToolName());
  }

  private JsonNode postData(String path, String json) throws Exception {
    return adminClient().postJson(path, json);
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
