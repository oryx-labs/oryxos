package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.mcp.McpServerAdmin;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** mcp-servers 端点切片：凭证回显掩码（FR-012）与「提交掩码 = 未修改」归并。 */
class McpApiControllerTest {

  private McpServerAdmin admin;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    admin = mock(McpServerAdmin.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new McpApiController(admin))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("list 回显掩码_env/headers 里的字面量凭证不明文泄露（FR-012）")
  void list_masksLiteralCredentials() throws Exception {
    when(admin.list())
        .thenReturn(
            List.of(
                new McpServerConfig(
                    "github",
                    "http",
                    null,
                    Map.of(),
                    "https://api.githubcopilot.com/mcp/",
                    Map.of("Authorization", "Bearer ghp_secret123456"),
                    120)));

    mvc.perform(get("/api/v1/mcp-servers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].headers.Authorization").value("****3456"))
        .andExpect(jsonPath("$.data[0].requestTimeoutSeconds").value(120));
  }

  @Test
  @DisplayName("add 自定义 requestTimeoutSeconds_传给管理服务并回显")
  void add_customTimeout_passesThrough() throws Exception {
    when(admin.add(any())).thenAnswer(invocation -> invocation.getArgument(0));

    mvc.perform(
            post("/api/v1/mcp-servers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"slow\",\"transport\":\"stdio\",\"command\":\"echo\","
                        + "\"env\":{},\"headers\":{},\"requestTimeoutSeconds\":240}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.requestTimeoutSeconds").value(240));

    ArgumentCaptor<McpServerConfig> captor = ArgumentCaptor.forClass(McpServerConfig.class);
    verify(admin).add(captor.capture());
    Assertions.assertEquals(240, captor.getValue().requestTimeoutSeconds());
  }

  @Test
  @DisplayName("auto 配置经管理 API 新建与编辑保留传输、完整端点和超时")
  void auto_createAndUpdateRoundTrip() throws Exception {
    when(admin.add(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(admin.update(eq("remote"), any())).thenAnswer(invocation -> invocation.getArgument(1));
    when(admin.list())
        .thenReturn(
            List.of(
                new McpServerConfig(
                    "remote",
                    "auto",
                    null,
                    Map.of(),
                    "https://example.invalid/team/mcp?tenant=a%2Fb",
                    Map.of("Authorization", "Bearer ${MCP_TOKEN}"),
                    120)));
    String body =
        """
        {"name":"remote","transport":"auto","url":"https://example.invalid/team/mcp?tenant=a%2Fb",
         "headers":{"Authorization":"Bearer ${MCP_TOKEN}"},"requestTimeoutSeconds":120}
        """;
    mvc.perform(post("/api/v1/mcp-servers").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.transport").value("auto"))
        .andExpect(jsonPath("$.data.url").value("https://example.invalid/team/mcp?tenant=a%2Fb"))
        .andExpect(jsonPath("$.data.headers.Authorization").value("****KEN}"))
        .andExpect(jsonPath("$.data.requestTimeoutSeconds").value(120));
    ArgumentCaptor<McpServerConfig> created = ArgumentCaptor.forClass(McpServerConfig.class);
    verify(admin).add(created.capture());
    Assertions.assertEquals(
        "Bearer ${MCP_TOKEN}", created.getValue().headers().get("Authorization"));
    mvc.perform(
            put("/api/v1/mcp-servers/remote")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("Bearer ${MCP_TOKEN}", "****KEN}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.transport").value("auto"))
        .andExpect(jsonPath("$.data.url").value("https://example.invalid/team/mcp?tenant=a%2Fb"))
        .andExpect(jsonPath("$.data.headers.Authorization").value("****KEN}"))
        .andExpect(jsonPath("$.data.requestTimeoutSeconds").value(120));
    ArgumentCaptor<McpServerConfig> updated = ArgumentCaptor.forClass(McpServerConfig.class);
    verify(admin).update(eq("remote"), updated.capture());
    Assertions.assertEquals(
        "Bearer ${MCP_TOKEN}", updated.getValue().headers().get("Authorization"));
  }

  @Test
  @DisplayName("update 回传掩码 env 值_视为未修改_保留原 token")
  void update_maskedEnvValue_keepsOriginal() throws Exception {
    McpServerConfig existing =
        new McpServerConfig(
            "gh",
            "stdio",
            "npx -y server",
            Map.of("GITHUB_TOKEN", "ghp_token1234"),
            null,
            Map.of(),
            180);
    when(admin.list()).thenReturn(List.of(existing));
    when(admin.update(eq("gh"), any())).thenAnswer(invocation -> invocation.getArgument(1));

    mvc.perform(
            put("/api/v1/mcp-servers/gh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"transport\":\"stdio\",\"command\":\"npx -y server\","
                        + "\"env\":{\"GITHUB_TOKEN\":\"****1234\"},\"headers\":{}}"))
        .andExpect(status().isOk());

    ArgumentCaptor<McpServerConfig> captor = ArgumentCaptor.forClass(McpServerConfig.class);
    verify(admin).update(eq("gh"), captor.capture());
    Assertions.assertEquals(
        "ghp_token1234", captor.getValue().env().get("GITHUB_TOKEN"), "掩码回填不得覆盖真实 token");
    Assertions.assertEquals(180, captor.getValue().requestTimeoutSeconds(), "省略 timeout 时保留原值");
  }

  @Test
  @DisplayName("update 新值（非掩码）_照常覆盖")
  void update_newValue_overwrites() throws Exception {
    McpServerConfig existing =
        new McpServerConfig(
            "gh",
            "stdio",
            "npx -y server",
            Map.of("GITHUB_TOKEN", "ghp_token1234"),
            null,
            Map.of());
    when(admin.list()).thenReturn(List.of(existing));
    when(admin.update(eq("gh"), any())).thenAnswer(invocation -> invocation.getArgument(1));

    mvc.perform(
            put("/api/v1/mcp-servers/gh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"transport\":\"stdio\",\"command\":\"npx -y server\","
                        + "\"env\":{\"GITHUB_TOKEN\":\"ghp_newtoken99\"},\"headers\":{},"
                        + "\"requestTimeoutSeconds\":240}"))
        .andExpect(status().isOk());

    ArgumentCaptor<McpServerConfig> captor = ArgumentCaptor.forClass(McpServerConfig.class);
    verify(admin).update(eq("gh"), captor.capture());
    Assertions.assertEquals("ghp_newtoken99", captor.getValue().env().get("GITHUB_TOKEN"));
    Assertions.assertEquals(240, captor.getValue().requestTimeoutSeconds());
  }

  @Test
  @DisplayName("update requestTimeoutSeconds 越界_返回400")
  void update_invalidTimeout_returns400() throws Exception {
    when(admin.list())
        .thenReturn(
            List.of(new McpServerConfig("gh", "stdio", "npx -y server", Map.of(), null, Map.of())));

    mvc.perform(
            put("/api/v1/mcp-servers/gh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"transport\":\"stdio\",\"command\":\"npx -y server\","
                        + "\"env\":{},\"headers\":{},\"requestTimeoutSeconds\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    org.hamcrest.Matchers.containsString("request_timeout/requestTimeoutSeconds")));
  }

  @Test
  @DisplayName("update 不存在_返回404")
  void update_unknown_returns404() throws Exception {
    when(admin.list()).thenReturn(List.of());

    mvc.perform(
            put("/api/v1/mcp-servers/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transport\":\"stdio\",\"command\":\"x\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }
}
