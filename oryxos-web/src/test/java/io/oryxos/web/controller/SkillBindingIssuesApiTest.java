package io.oryxos.web.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.InstalledSkillCatalog;
import io.oryxos.core.skill.SkillLoader;
import io.oryxos.core.skill.SkillRegistry;
import io.oryxos.core.skill.SkillService;
import io.oryxos.core.skill.SkillStore;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillBindingIssuesApiTest {

  @TempDir Path root;

  @Test
  void returnsStableActiveArchivedAndInvalidAgentIssues() throws Exception {
    createAgent(root.resolve("agents/z-active"), "z-active", true);
    createAgent(root.resolve("archive/a-archived-1"), "a-archived", true);
    Path invalid = Files.createDirectories(root.resolve("agents/missing-profile/skills"));
    Files.writeString(invalid.resolve("bad"), "ordinary");
    SkillLoader loader = new SkillLoader(root.resolve("skills"));
    SkillRegistry registry = new SkillRegistry();
    AgentSkillBindingService bindings = new AgentSkillBindingService(root, loader);
    SkillService service = new SkillService(new SkillStore(root), registry, loader, bindings);
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new SkillApiController(service, new InstalledSkillCatalog(registry), bindings))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(get("/api/v1/skills/binding-issues"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].agentName").value("a-archived-1"))
        .andExpect(jsonPath("$.data[0].agentState").value("ARCHIVED"))
        .andExpect(jsonPath("$.data[1].agentName").value("missing-profile"))
        .andExpect(jsonPath("$.data[1].agentState").value("INVALID"))
        .andExpect(jsonPath("$.data[2].agentName").value("z-active"))
        .andExpect(jsonPath("$.data[2].agentState").value("ACTIVE"));
  }

  private static void createAgent(Path directory, String name, boolean invalidBinding)
      throws Exception {
    Files.createDirectories(directory.resolve("skills"));
    Files.writeString(directory.resolve("AGENT.md"), "---\nname: " + name + "\n---\nbody");
    if (invalidBinding) {
      Files.writeString(directory.resolve("skills/bad"), "ordinary");
    }
  }
}
