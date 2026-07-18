package io.oryxos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 课件《第30节》验收 harness：WorkspaceApiControllerTest——目录树 + 读文件 + 防目录穿越。 */
class WorkspaceApiControllerTest {

  @TempDir Path oryxosRoot;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws IOException {
    Path agent = Files.createDirectories(oryxosRoot.resolve("agents").resolve("demo"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: demo\n---\n正文内容");
    Files.createDirectories(oryxosRoot.resolve("archive"));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceApiController(
                    oryxosRoot.toString(),
                    org.mockito.Mockito.mock(io.oryxos.core.agent.AgentLifecycleService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("tree 返回 agents/archive 结构、可钻进 Agent 目录列文件")
  void tree_returnsAgentsAndArchive() throws Exception {
    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.children[0].name").value("agents"))
        .andExpect(jsonPath("$.data.children[0].children[0].name").value("demo"))
        .andExpect(jsonPath("$.data.children[1].name").value("archive"));
  }

  @Test
  @DisplayName("file?path=../../etc/passwd 目录穿越 → 400")
  void file_pathTraversal_returns400() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("正常文件返回内容")
  void file_validPath_returnsContent() throws Exception {
    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/AGENT.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("---\nname: demo\n---\n正文内容"));
  }
}
