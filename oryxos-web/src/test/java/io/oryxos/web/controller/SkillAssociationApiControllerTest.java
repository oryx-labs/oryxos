package io.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.profile.Profile;
import io.oryxos.web.GlobalExceptionHandler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillAssociationApiControllerTest {

  private AgentLifecycleService lifecycle;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    lifecycle = mock(AgentLifecycleService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SkillAssociationApiController(lifecycle))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void associateUpdatesAgentProfile() throws Exception {
    Profile before = profile(List.of());
    Profile after = profile(List.of("web-research"));
    when(lifecycle.get("ops")).thenReturn(Optional.of(before));
    when(lifecycle.setSkillAssociation("ops", "web-research", true)).thenReturn(after);

    mvc.perform(put("/api/v1/skills/web-research/agents/ops"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.skills[0]").value("web-research"));
    verify(lifecycle).setSkillAssociation("ops", "web-research", true);
  }

  private static Profile profile(List<String> skills) {
    return new Profile(
        "ops",
        null,
        null,
        new Profile.ProviderRef("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        skills,
        Profile.Settings.defaults());
  }
}
