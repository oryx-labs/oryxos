package io.oryxos.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentLoader;
import io.oryxos.core.agent.AgentScheduler;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.provider.ProviderService;
import io.oryxos.core.skill.AgentSkillCatalog;
import io.oryxos.core.skill.AgentSkillCoordinator;
import io.oryxos.core.skill.AgentSkillLockRegistry;
import io.oryxos.core.skill.SkillContentValidator;
import io.oryxos.core.skill.SkillLease;
import io.oryxos.core.skill.SkillLimits;
import io.oryxos.core.skill.SkillMetadataReader;
import io.oryxos.web.GlobalExceptionHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
  private AgentLifecycleService lifecycle;
  private AgentSkillCoordinator coordinator;
  private SkillLease lease;

  @BeforeEach
  void setUp() throws IOException {
    Path agent = Files.createDirectories(oryxosRoot.resolve("agents").resolve("demo"));
    Files.writeString(agent.resolve("AGENT.md"), "---\nname: demo\n---\n正文内容");
    Files.createDirectories(oryxosRoot.resolve("archive"));
    lifecycle = mock(AgentLifecycleService.class);
    coordinator = mock(AgentSkillCoordinator.class);
    lease = mock(SkillLease.class);
    when(coordinator.openRequest("demo")).thenReturn(lease);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceApiController(oryxosRoot.toString(), lifecycle, coordinator))
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

  @Test
  @DisplayName("读取受管 Skill 文件持有同一 Agent 请求读租约")
  void file_managedSkill_usesReadLease() throws Exception {
    Path skill = Files.createDirectories(oryxosRoot.resolve("agents/demo/skills/weather"));
    Files.writeString(skill.resolve("SKILL.md"), "skill-content");

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/skills/weather/SKILL.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("skill-content"));

    verify(coordinator).openRequest("demo");
    verify(lease).close();
  }

  @Test
  @DisplayName("写受管 Skill 文件路由到生命周期写锁/原子写入口")
  void writeFile_managedSkill_routesToLifecycle() throws Exception {
    Files.createDirectories(oryxosRoot.resolve("agents/demo/skills/weather"));

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/skills/weather/SKILL.md\",\"content\":\"new\"}"))
        .andExpect(status().isOk());

    verify(lifecycle).writeManagedSkillFile("demo", "skills/weather/SKILL.md", "new");
  }

  @Test
  @DisplayName("受管 Skill 写入等待请求读租约，目标文件只呈现旧值或原子提交后的新值")
  void writeFile_managedSkill_waitsForRequestLeaseAndPublishesAtomically() throws Exception {
    Path skill = Files.createDirectories(oryxosRoot.resolve("agents/demo/skills/weather"));
    Path entry = skill.resolve("SKILL.md");
    String oldContent = "---\nname: weather\ndescription: old\n---\nold body\n";
    String newContent = "---\nname: weather\ndescription: new\n---\nnew body\n";
    Files.writeString(entry, oldContent);

    Profile profile =
        new Profile(
            "demo",
            "demo",
            null,
            new Profile.ProviderRef("mock", "mock", null),
            List.of("read_file"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Profile.Settings.defaults());
    ProfileRegistry profiles = new ProfileRegistry(Map.of("demo", profile));
    SkillLimits limits = SkillLimits.defaults();
    AgentSkillCoordinator realCoordinator =
        new AgentSkillCoordinator(
            oryxosRoot.resolve("agents"),
            profiles,
            new AgentSkillCatalog(
                oryxosRoot.resolve("agents"),
                new SkillMetadataReader(),
                new SkillContentValidator(),
                limits),
            new AgentSkillLockRegistry());
    AgentStore realStore = new AgentStore(oryxosRoot, realCoordinator);
    CountDownLatch writerEntered = new CountDownLatch(1);
    AgentLifecycleService realLifecycle =
        new AgentLifecycleService(
            mock(AgentLoader.class),
            profiles,
            mock(AgentScheduler.class),
            realStore,
            mock(ProviderService.class),
            "mock",
            "mock",
            "mock",
            realCoordinator) {
          @Override
          public Path writeManagedSkillFile(String name, String relativePath, String content) {
            writerEntered.countDown();
            return super.writeManagedSkillFile(name, relativePath, content);
          }
        };
    MockMvc concurrentMvc =
        MockMvcBuilders.standaloneSetup(
                new WorkspaceApiController(oryxosRoot.toString(), realLifecycle, realCoordinator))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    try (SkillLease request = realCoordinator.openRequest("demo");
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> write =
          executor.submit(
              () -> {
                try {
                  concurrentMvc
                      .perform(
                          post("/api/v1/workspace/file")
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(
                                  "{\"path\":\"agents/demo/skills/weather/SKILL.md\","
                                      + "\"content\":\""
                                      + newContent.replace("\n", "\\n")
                                      + "\"}"))
                      .andExpect(status().isOk());
                } catch (Exception error) {
                  throw new IllegalStateException(error);
                }
              });

      assertTrue(writerEntered.await(2, TimeUnit.SECONDS));
      assertFalse(write.isDone(), "writer must wait while the request read lease is active");
      assertEquals(oldContent, Files.readString(entry));
      assertFalse(
          write.isDone(),
          "the write cannot expose an intermediate target before the request lease closes");
      request.close();
      write.get(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS);
    }

    assertEquals(newContent, Files.readString(entry));
    try (var children = Files.list(skill)) {
      assertEquals(
          List.of("SKILL.md"), children.map(path -> path.getFileName().toString()).toList());
    }
  }

  @Test
  @DisplayName("受管 Skill 路径包含 symlink 时读写都拒绝")
  void managedSkill_symlinkPath_rejected() throws Exception {
    Path outside = Files.createDirectories(oryxosRoot.resolve("outside"));
    Files.writeString(outside.resolve("SKILL.md"), "secret");
    Path skills = Files.createDirectories(oryxosRoot.resolve("agents/demo/skills"));
    Files.createSymbolicLink(skills.resolve("weather"), outside);

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/skills/weather/SKILL.md"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/skills/weather/nested/SKILL.md\",\"content\":\"escaped\"}"))
        .andExpect(status().isBadRequest());

    verify(lifecycle, never())
        .writeManagedSkillFile(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    assertFalse("escaped".equals(Files.readString(outside.resolve("SKILL.md"))));
    assertFalse(Files.exists(outside.resolve("nested")), "拒绝前不能在链接目标创建父目录");
  }

  @Test
  @DisplayName("Agent 普通嵌套目录里的 skills 段仍走 generic writer")
  void writeFile_nonDirectAgentSkillPath_remainsGeneric() throws Exception {
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"path\":\"agents/demo/nested/skills/weather/SKILL.md\",\"content\":\"x\"}"))
        .andExpect(status().isOk());

    assertEquals(
        "x", Files.readString(oryxosRoot.resolve("agents/demo/nested/skills/weather/SKILL.md")));
    verify(lifecycle, never())
        .writeManagedSkillFile(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("旧版 agents/<agent>/skills/*.md 保持可读写")
  void legacyFlatSkillFile_remainsReadableAndWritable() throws Exception {
    Path legacy =
        Files.createDirectories(oryxosRoot.resolve("agents/demo/skills")).resolve("legacy.md");
    Files.writeString(legacy, "old");

    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/skills/legacy.md"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("old"));
    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/demo/skills/legacy.md\",\"content\":\"new\"}"))
        .andExpect(status().isOk());

    assertEquals("new", Files.readString(legacy));
    verify(coordinator, never()).openRequest("demo");
    verify(lifecycle, never())
        .writeManagedSkillFile(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("archive 可浏览但不能通过通用 Workspace 写接口篡改")
  void writeFile_archivePath_isReadOnly() throws Exception {
    Path archived = Files.writeString(oryxosRoot.resolve("archive/archive.yml"), "original");

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"archive/archive.yml\",\"content\":\"tampered\"}"))
        .andExpect(status().isBadRequest());

    assertEquals("original", Files.readString(archived));
  }

  @Test
  @DisplayName("保留目录与文件的大小写变体不能绕过 Skill 协调或归档只读")
  void writeFile_reservedCaseVariants_areRejected() throws Exception {
    Path archived = Files.writeString(oryxosRoot.resolve("archive/archive.yml"), "original");
    Path skill = Files.createDirectories(oryxosRoot.resolve("agents/demo/skills/weather"));
    Path marker = Files.writeString(skill.resolve(".oryxos-disabled"), "");

    for (String path :
        List.of(
            "Archive/archive.yml",
            "Agents/demo/skills/weather/SKILL.md",
            "agents/demo/Skills/weather/SKILL.md",
            "agents/demo/agent.md",
            "agents/demo/skills/weather/.ORYXOS-DISABLED")) {
      mvc.perform(
              post("/api/v1/workspace/file")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"path\":\"" + path + "\",\"content\":\"tampered\"}"))
          .andExpect(status().isBadRequest());
    }

    assertEquals("original", Files.readString(archived));
    assertEquals(0L, Files.size(marker));
    verify(lifecycle, never())
        .writeManagedSkillFile(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Skill 根和包目录不能走通用 writer，legacy skills/*.md 仍兼容")
  void writeFile_skillContainerPaths_areRejected() throws Exception {
    Files.createDirectories(oryxosRoot.resolve("agents/demo/skills/weather"));

    for (String path : List.of("agents/demo/skills", "agents/demo/skills/weather")) {
      mvc.perform(
              post("/api/v1/workspace/file")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"path\":\"" + path + "\",\"content\":\"tampered\"}"))
          .andExpect(status().isBadRequest());
    }

    assertTrue(Files.isDirectory(oryxosRoot.resolve("agents/demo/skills/weather")));
    verify(lifecycle, never())
        .writeManagedSkillFile(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Agent 名可为 skills，不应被误判为受管 Skill")
  void agentNamedSkills_remainsGeneric() throws Exception {
    Files.createDirectories(oryxosRoot.resolve("agents/skills/assets"));

    mvc.perform(
            post("/api/v1/workspace/file")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\":\"agents/skills/assets/note.txt\",\"content\":\"ok\"}"))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/workspace/file").param("path", "agents/skills/assets/note.txt"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").value("ok"));

    verify(coordinator, never()).openRequest("skills");
  }

  @Test
  @DisplayName("tree 不跟随工作区内 symlink 泄露根外目录内容")
  void tree_doesNotTraverseSymlinkDirectories() throws Exception {
    Path outside = Files.createDirectories(oryxosRoot.resolve("outside-tree"));
    Files.writeString(outside.resolve("secret.txt"), "secret");
    Files.createSymbolicLink(oryxosRoot.resolve("agents/demo/link"), outside);

    String json =
        mvc.perform(get("/api/v1/workspace/tree"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertFalse(json.contains("secret.txt"));
  }
}
