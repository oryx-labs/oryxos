package io.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.LinkStatus;
import io.oryxos.core.skill.PublicSkillCatalog;
import io.oryxos.core.skill.PublicSkillDescriptor;
import io.oryxos.core.skill.SkillAssociation;
import io.oryxos.core.skill.SkillAssociationManager;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillAssociationApiControllerTest {

  private SkillAssociationManager associations;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    associations = mock(SkillAssociationManager.class);
    PublicSkillCatalog publicSkills = mock(PublicSkillCatalog.class);
    SkillAssociation link =
        new SkillAssociation(
            "ops",
            "web-research",
            Path.of("/workspace/agents/ops/skills/web-research"),
            "../../../skills/web-research",
            LinkStatus.VALID,
            SkillStatus.ENABLED,
            true,
            null);
    when(associations.associate("ops", "web-research")).thenReturn(link);
    when(publicSkills.get("web-research")).thenReturn(mock(PublicSkillDescriptor.class));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new SkillAssociationApiController(associations, publicSkills))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void deprecatedReverseRouteDelegatesToTheCanonicalLinkManager() throws Exception {
    mvc.perform(put("/api/v1/skills/web-research/agents/ops"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.skillName").value("web-research"));
    verify(associations).associate("ops", "web-research");
  }
}
