package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.tool.ToolRegistry;
import io.oryxos.tool.mcp.McpClientService;
import io.oryxos.tool.mcp.McpConfigLoader;
import io.oryxos.tool.mcp.McpServerAdminService;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.controller.McpApiController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * {@code GET /api/v1/mcp-servers} 不回显字面量凭证——键名一个敏感词都不含的（{@code DATABASE_URI} / {@code X-Auth}）
 * 同样不回显：凭证装进哪个键由配置的人决定，值本身才是判据。
 *
 * <p>整条链路用真的组件跑：临时 {@code mcp_servers.yaml} → {@link McpConfigLoader} → {@link
 * McpServerAdminService} → {@link McpApiController} 出 JSON。写回那半用管理台的真实形状验证——把列表返回的对象原样 PUT 回去（取回 →
 * 改 → 存）， 断言盘上的真值没被回显出来的掩码顶掉：回显掩码而不认掩码，就是 #818 修过的那个洞。
 */
class McpCredentialMaskRoundTripTest {

  /** 真值只出现在这里：断言查的是它有没有进响应体、有没有被写回盘上。 */
  private static final String DSN = "postgres://oryxos:hunter2-pw@db.internal:5432/reporting";

  private static final String X_AUTH = "Bearer sk-live-9f3a2b7c1d4e";

  @TempDir Path tempDir;

  @Test
  @DisplayName("列表不回显 DATABASE_URI/X-Auth 这类键下的字面量凭证")
  void listDoesNotEchoLiteralCredentialsUnderKeysThatDoNotNameThem() throws Exception {
    Harness harness = harness();

    String body = harness.list();

    assertFalse(body.contains("hunter2-pw"), "连接串里的库口令明文出现在列表响应里: " + body);
    assertFalse(body.contains("sk-live-9f3a2b7c1d4e"), "自定义头里的令牌明文出现在列表响应里: " + body);
  }

  @Test
  @DisplayName("纯 ${VAR} 引用的值原样回显：文件里本来就只有引用，没有真值")
  void listKeepsAPlaceholderReferenceVisible() throws Exception {
    Harness harness = harness();

    assertTrue(
        harness.list().contains("${POSTGRES_DSN}"), "占位符不是凭证本体，掩掉它只是让运维看不出这个 server 引用的是哪个环境变量");
  }

  @Test
  @DisplayName("把列表返回的对象原样 PUT 回去_盘上的真值不被掩码顶掉")
  void listThenPutLeavesTheStoredCredentialsIntact() throws Exception {
    Harness harness = harness();

    String listed = harness.list();
    for (String name : List.of("reporting-db", "remote-mcp", "placeholder-db")) {
      harness.put(name, listed);
    }

    assertEquals(DSN, harness.env("reporting-db", "DATABASE_URI"), "掩码被写回盘上，真实连接串丢了");
    assertEquals("production", harness.env("reporting-db", "NODE_ENV"), "掩码被写回盘上，字面量设置丢了");
    assertEquals(X_AUTH, harness.header("remote-mcp", "X-Auth"), "掩码被写回盘上，真实令牌丢了");
    assertEquals(
        "${POSTGRES_DSN}", harness.env("placeholder-db", "DATABASE_URI"), "占位符应当原样落盘，未被解析成明文");
  }

  /**
   * 一份真的 {@code mcp_servers.yaml} + 真的 loader / 管理服务 + 真的控制器。连接工厂是测试替身：这条用例验的是文件与 响应之间的往返，不是 MCP
   * 连接本身。
   */
  private Harness harness() throws Exception {
    Path configFile = tempDir.resolve(".oryxos").resolve("mcp_servers.yaml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(
        configFile,
        """
        servers:
          - name: reporting-db
            transport: stdio
            command: /bin/true
            env:
              DATABASE_URI: "%s"
              NODE_ENV: production
          - name: remote-mcp
            transport: streamable
            url: https://mcp.example.invalid/mcp
            headers:
              X-Auth: "%s"
          - name: placeholder-db
            transport: stdio
            command: /bin/true
            env:
              DATABASE_URI: "${POSTGRES_DSN}"
        """
            .formatted(DSN, X_AUTH));

    McpConfigLoader loader = new McpConfigLoader(configFile);
    McpClientService clientService = new McpClientService(loader, config -> stubClient());
    McpServerAdminService admin =
        new McpServerAdminService(loader, clientService, new ToolRegistry());
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(new McpApiController(admin))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    return new Harness(mvc, loader);
  }

  private static McpSyncClient stubClient() {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(), null));
    return client;
  }

  /** 管理台看见的那两个端点：列表出 JSON，编辑表单把同一个对象放进 PUT。 */
  private static final class Harness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final McpConfigLoader loader;

    Harness(MockMvc mvc, McpConfigLoader loader) {
      this.mvc = mvc;
      this.loader = loader;
    }

    String list() throws Exception {
      return mvc.perform(get("/api/v1/mcp-servers"))
          .andExpect(status().isOk())
          .andReturn()
          .getResponse()
          .getContentAsString();
    }

    /** 列表返回的 {@code data} 里那一条，原样提交——这就是管理台「取回 → 改 → 存」的请求体。 */
    void put(String name, String listed) throws Exception {
      JsonNode server = null;
      for (JsonNode node : MAPPER.readTree(listed).get("data")) {
        if (node.get("name").asText().equals(name)) {
          server = node;
        }
      }
      assertNotNull(server, "列表里没有 " + name + ": " + listed);
      mvc.perform(
              MockMvcRequestBuilders.put("/api/v1/mcp-servers/" + name)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(server.toString()))
          .andExpect(status().isOk());
    }

    String env(String name, String key) {
      return stored(name).env().get(key);
    }

    String header(String name, String key) {
      return stored(name).headers().get(key);
    }

    /** 盘上这一份（loader 直读文件，不经任何回显）。 */
    private McpServerConfig stored(String name) {
      return loader.loadRaw().stream()
          .filter(c -> c.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new AssertionError("盘上没有 " + name));
    }
  }
}
