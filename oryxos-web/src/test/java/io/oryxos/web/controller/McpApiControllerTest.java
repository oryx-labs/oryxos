package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
                    Map.of("Authorization", "Bearer ghp_secret123456"))));

    mvc.perform(get("/api/v1/mcp-servers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].headers.Authorization").value("****3456"));
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
            Map.of());
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
                        + "\"env\":{\"GITHUB_TOKEN\":\"ghp_newtoken99\"},\"headers\":{}}"))
        .andExpect(status().isOk());

    ArgumentCaptor<McpServerConfig> captor = ArgumentCaptor.forClass(McpServerConfig.class);
    verify(admin).update(eq("gh"), captor.capture());
    Assertions.assertEquals("ghp_newtoken99", captor.getValue().env().get("GITHUB_TOKEN"));
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
