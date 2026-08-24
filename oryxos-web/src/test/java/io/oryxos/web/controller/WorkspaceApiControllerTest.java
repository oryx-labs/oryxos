package io.oryxos.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 课件《第30节》验收 harness：WorkspaceApiControllerTest——目录树 + 读文件 + 防目录穿越。 */
class WorkspaceApiControllerTest {

  @TempDir Path oryxosRoot;
  private MockMvc mvc;
  private io.oryxos.core.agent.AgentLifecycleService lifecycle;

  @BeforeEach
  void setUp() throws IOException {
    Path agent = Files.createDirectories(oryxosRoot.resolve("agents").resolve("demo"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: demo\n---\n正文内容");
    Files.createDirectories(oryxosRoot.resolve("archive"));
    lifecycle = org.mockito.Mockito.mock(io.oryxos.core.agent.AgentLifecycleService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceApiController(oryxosRoot.toString(), lifecycle))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("tree 返回 agents/archive 结构、可钻进 Agent 目录列文件")
  void tree_returnsAgentsAndArchive() throws Exception {
    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.children[*].name")
                .value(org.hamcrest.Matchers.hasItems("agents", "skills", "output", "archive")))
        .andExpect(jsonPath("$.data.children[0].children[0].name").value("demo"));
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

  @Test
  @DisplayName("download 正常文件：附件头 + 原始字节")
  void download_validPath_returnsAttachment() throws Exception {
    Path report = oryxosRoot.resolve("agents").resolve("demo").resolve("output");
    Files.createDirectories(report);
    Files.writeString(report.resolve("report.md"), "# 研报\n今日无异常");
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/output/report.md"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", containsString("attachment")))
        .andExpect(header().string("Content-Disposition", containsString("report.md")))
        .andExpect(
            content().bytes("# 研报\n今日无异常".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("download 目录穿越 → 400")
  void download_pathTraversal_returns400() throws Exception {
    mvc.perform(get("/api/v1/workspace/download").param("path", "../../etc/passwd"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("download 文件不存在 → 404")
  void download_missingFile_returns404() throws Exception {
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/output/nope.md"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("read/download/write 均拒绝经父软连接逃逸，tree 把链接作为叶节点")
  void symlinkEscapeIsRejectedAndTreeDoesNotFollow() throws Exception {
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-workspace"));
    Files.writeString(outside.resolve("secret.txt"), "SECRET");
    Files.createSymbolicLink(oryxosRoot.resolve("agents/demo/escape"), outside);

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/escape/secret.txt"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/workspace/download").param("path", "agents/demo/escape/secret.txt"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/escape/new.txt\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("new.txt")));

    mvc.perform(get("/api/v1/workspace/tree"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$..[?(@.name == 'escape')].type").value("link"))
        .andExpect(jsonPath("$..[?(@.name == 'escape')].children.length()").value(0));
  }

  @Test
  @DisplayName("工作区写入口禁止直写 MEMORY.md（须走 save_memory）")
  void writeMemoryMdIsRejected() throws Exception {
    Path memory = Files.createDirectories(oryxosRoot.resolve("agents/demo"));
    Files.writeString(memory.resolve("MEMORY.md"), "## 核心记忆\n- keep\n## 归档记忆\n");

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/MEMORY.md\",\"content\":\"## 核心记忆\\n## 归档记忆\\n- hijack\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals(
        "## 核心记忆\n- keep\n## 归档记忆\n",
        Files.readString(oryxosRoot.resolve("agents/demo/MEMORY.md")));
  }

  @Test
  @DisplayName("工作区写入口禁止写 Agent skills 绑定视图")
  void writeThroughAgentSkillsIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("写 AGENT.md 走 lifecycle.update，不直接落盘")
  void writeAgentMarkdown_goesThroughUpdate() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/AGENT.md\",\"content\":\"---\\nname: demo\\n---\\n新正文\"}"))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(lifecycle).update("demo", "---\nname: demo\n---\n新正文");
    org.junit.jupiter.api.Assertions.assertEquals(
        "---\nname: demo\n---\n正文内容", Files.readString(oryxosRoot.resolve("agents/demo/AGENT.md")));
  }

  @Test
  @DisplayName("agent.md 大小写不同仍走 update，不能绕过重注册")
  void writeAgentMarkdown_wrongCaseStillGoesThroughUpdate() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/agent.md\",\"content\":\"---\\nname: demo\\n---\\nbypass\"}"))
        .andExpect(status().isOk());
    org.mockito.Mockito.verify(lifecycle).update("demo", "---\nname: demo\n---\nbypass");
    try (java.util.stream.Stream<Path> entries = Files.list(oryxosRoot.resolve("agents/demo"))) {
      org.junit.jupiter.api.Assertions.assertFalse(
          entries.anyMatch(p -> "agent.md".equals(String.valueOf(p.getFileName()))),
          "不得另写一份 agent.md 绕过 update");
    }
  }

  @Test
  @DisplayName("Skills/ 大小写不同仍禁止写入绑定视图")
  void writeThroughAgentSkillsWrongCaseIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/Skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(
        Files.exists(oryxosRoot.resolve("agents/demo/Skills/report/SKILL.md")));
  }

  @Test
  @DisplayName("工作区写入口禁止直接或经归档 Agent 软连接修改共享 Skill")
  void writeThroughSharedSkillPathsIsRejected() throws Exception {
    Path shared = Files.createDirectories(oryxosRoot.resolve("skills/report"));
    Path skillFile = Files.writeString(shared.resolve("SKILL.md"), "original");
    Path archivedLinks = Files.createDirectories(oryxosRoot.resolve("archive/ops-old/skills"));
    Files.createSymbolicLink(archivedLinks.resolve("report"), Path.of("../../../skills/report"));

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"archive/ops-old/skills/report/SKILL.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals("original", Files.readString(skillFile));
  }

  @Test
  @DisplayName("工作区读取悬空软连接返回 400 而非 500")
  void danglingLinkReturnsBadRequest() throws Exception {
    Files.createSymbolicLink(
        oryxosRoot.resolve("agents/demo/dangling"), Path.of("../../skills/missing"));

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/dangling/SKILL.md"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }
}
