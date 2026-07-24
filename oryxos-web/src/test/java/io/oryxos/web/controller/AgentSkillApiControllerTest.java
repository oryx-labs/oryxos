package io.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.LinkStatus;
import io.oryxos.core.skill.PublicSkillCatalog;
import io.oryxos.core.skill.SkillAssociation;
import io.oryxos.core.skill.SkillAssociationManager;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentSkillApiControllerTest {

  private SkillAssociationManager associations;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    associations = mock(SkillAssociationManager.class);
    PublicSkillCatalog publicSkills = mock(PublicSkillCatalog.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new AgentSkillApiController(associations, publicSkills))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listsFilesystemDerivedAssociations() throws Exception {
    when(associations.list("ops")).thenReturn(List.of(linked("ops", "weather")));

    mvc.perform(get("/api/v1/agents/ops/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].skillName").value("weather"))
        .andExpect(jsonPath("$.data[0].target").value("../../../skills/weather"))
        .andExpect(jsonPath("$.data[0].discoverable").value(true));
  }

  @Test
  void associatesAndUnlinksWithoutAnAgentMarkdownPayload() throws Exception {
    SkillAssociation link = linked("ops", "weather");
    when(associations.associate("ops", "weather")).thenReturn(link);
    when(associations.unlink("ops", "weather")).thenReturn(link);

    mvc.perform(put("/api/v1/agents/ops/skills/weather"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.linkStatus").value("valid"));
    mvc.perform(delete("/api/v1/agents/ops/skills/weather")).andExpect(status().isOk());

    verify(associations).associate("ops", "weather");
    verify(associations).unlink("ops", "weather");
  }

  private static SkillAssociation linked(String agent, String skill) {
    return new SkillAssociation(
        agent,
        skill,
        Path.of("/workspace/agents", agent, "skills", skill),
        "../../../skills/" + skill,
        LinkStatus.VALID,
        SkillStatus.ENABLED,
        true,
        null);
  }
}
