package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentExecutionService;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.SkillCatalogEntry;
import io.oryxos.core.skill.SkillMetadataReader;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentSkillBindingApiTest {

  @TempDir Path root;

  private AgentLifecycleService lifecycle;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    Files.createDirectories(root.resolve("agents/ops"));
    Files.writeString(root.resolve("agents/ops/AGENT.md"), "---\nname: ops\n---\nbody");
    skill("report", "报告");
    skill("web", "调研");
    AgentSkillBindingService bindings =
        new AgentSkillBindingService(root, new SkillMetadataReader());
    Profile profile = profile();
    ProfileRegistry profiles = new ProfileRegistry(Map.of("ops", profile));
    lifecycle = mock(AgentLifecycleService.class);
    when(lifecycle.list()).thenReturn(List.of(profile));
    when(lifecycle.saveFiles(eq("ops"), org.mockito.ArgumentMatchers.any(), eq(List.of("web"))))
        .thenReturn(profile);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AgentApiController(
                    lifecycle,
                    mock(AgentService.class),
                    mock(SessionManager.class),
                    profiles,
                    mock(MemoryService.class),
                    mock(AgentExecutionService.class),
                    bindings,
                    (q, visibility) ->
                        List.of(
                            new SkillCatalogEntry(
                                "report", "报告", SkillCatalogEntry.Visibility.PUBLIC, "test", true),
                            new SkillCatalogEntry(
                                "web", "调研", SkillCatalogEntry.Visibility.PRIVATE, "test", true))))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void bindingCrudReplaceAndAgentViewUseLiveLinks() throws Exception {
    mvc.perform(put("/api/v1/agents/ops/skills/report"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings[0].name").value("report"))
        .andExpect(jsonPath("$.data.bindings[0].skillFile").isNotEmpty());

    mvc.perform(get("/api/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].skills[0]").value("report"));

    mvc.perform(
            put("/api/v1/agents/ops/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skills\":[\"web\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings[0].name").value("web"));

    mvc.perform(delete("/api/v1/agents/ops/skills/web"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bindings").isEmpty());
  }

  @Test
  void saveFilesPassesSkillBindingSidecar() throws Exception {
    mvc.perform(
            post("/api/v1/agents/ops/files")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"files\":{\"AGENT.md\":\"---\\nname: ops\\n---\\nbody\"},\"skillBindings\":[\"web\"]}"))
        .andExpect(status().isOk());

    verify(lifecycle)
        .saveFiles(
            eq("ops"), eq(Map.of("AGENT.md", "---\nname: ops\n---\nbody")), eq(List.of("web")));
  }

  @Test
  void missingSkillReturns404BeforeCatalogValidation() throws Exception {
    mvc.perform(put("/api/v1/agents/ops/skills/missing"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    mvc.perform(
            put("/api/v1/agents/ops/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skills\":[\"missing\"]}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  private void skill(String name, String description) throws Exception {
    Path dir = Files.createDirectories(root.resolve("skills").resolve(name));
    Files.writeString(
        dir.resolve("SKILL.md"),
        "---\nname: " + name + "\ndescription: " + description + "\n---\nbody");
  }

  private static Profile profile() {
    return new Profile(
        "ops",
        "运维",
        null,
        new Profile.ProviderRef("mock", "mock", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Profile.Settings.defaults());
  }
}
