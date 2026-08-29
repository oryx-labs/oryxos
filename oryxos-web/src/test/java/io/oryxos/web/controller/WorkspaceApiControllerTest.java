package io.oryxos.web.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
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
  @DisplayName("write 在 createDirectories 后复检路径，阻断父路径被换成外向软链")
  void writeRechecksPathAfterCreateDirectories() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "需要 POSIX 软链支持");
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-recheck"));
    String rel = "agents/demo/hook/nested/file.txt";
    Path hook = oryxosRoot.resolve("agents/demo/hook");
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(hook));

    // 模拟 TOCTOU：首次 resolve 时 hook 尚不存在，建目录前被换成外向软链
    RealPathBoundary.requireWithin(oryxosRoot, oryxosRoot.resolve(rel).normalize());
    Files.createSymbolicLink(hook, outside);
    Files.createDirectories(oryxosRoot.resolve(rel).getParent());

    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(oryxosRoot, oryxosRoot.resolve(rel).normalize()));
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("nested/file.txt")));

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"" + rel + "\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertFalse(Files.exists(outside.resolve("nested/file.txt")));
  }

  @Test
  @DisplayName("file 读前复检路径，阻断目标被换成外向软链")
  void fileRechecksPathBeforeRead() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "需要 POSIX 软链支持");
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-read-recheck"));
    Files.writeString(outside.resolve("secret.txt"), "leaked");
    String rel = "agents/demo/notes.md";
    Path notes = oryxosRoot.resolve(rel);
    Files.writeString(notes, "inside");

    RealPathBoundary.requireWithin(oryxosRoot, notes.normalize());
    Files.delete(notes);
    Files.createSymbolicLink(notes, outside.resolve("secret.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(oryxosRoot, notes.normalize()));

    mvc.perform(get("/api/v1/workspace/file").param("path", rel))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("download 读前复检路径，阻断目标被换成外向软链")
  void downloadRechecksPathBeforeRead() throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "需要 POSIX 软链支持");
    Path outside = Files.createDirectories(oryxosRoot.resolveSibling("outside-download-recheck"));
    Files.writeString(outside.resolve("secret.bin"), "leaked");
    String rel = "agents/demo/output/report.md";
    Path report = oryxosRoot.resolve(rel);
    Files.createDirectories(report.getParent());
    Files.writeString(report, "inside");

    RealPathBoundary.requireWithin(oryxosRoot, report.normalize());
    Files.delete(report);
    Files.createSymbolicLink(report, outside.resolve("secret.bin"));
    assertThrows(
        IllegalArgumentException.class,
        () -> RealPathBoundary.requireWithin(oryxosRoot, report.normalize()));

    mvc.perform(get("/api/v1/workspace/download").param("path", rel))
        .andExpect(status().isBadRequest());
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
  @DisplayName("工作区写入口禁止经软链改写 MEMORY.md（notes.md → MEMORY.md）")
  void writeViaSymlinkToMemoryMdIsRejected() throws Exception {
    Path agentDir = Files.createDirectories(oryxosRoot.resolve("agents/demo"));
    Path memory = agentDir.resolve("MEMORY.md");
    Files.writeString(memory, "## 核心记忆\n- keep\n## 归档记忆\n");
    Path alias = agentDir.resolve("notes.md");
    try {
      Files.createSymbolicLink(alias, memory.getFileName());
    } catch (IOException | UnsupportedOperationException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/notes.md\",\"content\":\"## 核心记忆\\n## 归档记忆\\n- hijack\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals(
        "## 核心记忆\n- keep\n## 归档记忆\n", Files.readString(memory));
  }

  @Test
  @DisplayName("工作区写入口禁止直写 channels.yaml / mcp_servers.yaml")
  void writeAdminConfigFilesIsRejected() throws Exception {
    Path channels = Files.writeString(oryxosRoot.resolve("channels.yaml"), "channels: []\n");
    Path mcp = Files.writeString(oryxosRoot.resolve("mcp_servers.yaml"), "servers: []\n");
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"channels.yaml\",\"content\":\"channels: [{hijack: true}]\\n\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"mcp_servers.yaml\",\"content\":\"servers: [{hijack: true}]\\n\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals("channels: []\n", Files.readString(channels));
    org.junit.jupiter.api.Assertions.assertEquals("servers: []\n", Files.readString(mcp));
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
  @DisplayName("工作区写入口禁止直接或经归档路径段修改共享 Skill")
  void writeThroughSharedSkillPathsIsRejected() throws Exception {
    Path shared = Files.createDirectories(oryxosRoot.resolve("skills/report"));
    Path skillFile = Files.writeString(shared.resolve("SKILL.md"), "original");
    // 不建软链：路径段守卫已拒 skills/**；归档侧同名 skills 段亦拒（免 Windows 建链权限）
    Files.createDirectories(oryxosRoot.resolve("archive/ops-old/skills"));

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
  @DisplayName("工作区写入口禁止 Knowledge 绑定视图与共享实体")
  void writeThroughKnowledgePathsIsRejected() throws Exception {
    Path shared = Files.createDirectories(oryxosRoot.resolve("knowledge/ops"));
    Path doc = Files.writeString(shared.resolve("doc.md"), "original");

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"knowledge/ops/doc.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/knowledge/ops/doc.md\",\"content\":\"bad\"}"))
        .andExpect(status().isBadRequest());
    org.junit.jupiter.api.Assertions.assertEquals("original", Files.readString(doc));
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
