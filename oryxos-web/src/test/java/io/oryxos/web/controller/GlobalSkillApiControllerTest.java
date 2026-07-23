package io.oryxos.web.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.GlobalSkillManagementService;
import io.oryxos.core.skill.SkillDescriptor;
import io.oryxos.core.skill.SkillMetadata;
import io.oryxos.core.skill.SkillSource;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.web.GlobalExceptionHandler;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GlobalSkillApiControllerTest {

  private GlobalSkillManagementService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(GlobalSkillManagementService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new GlobalSkillApiController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listsPublicSkillsWithAgentAssociations() throws Exception {
    when(service.list()).thenReturn(List.of(skill()));
    when(service.associatedAgents("weather")).thenReturn(List.of("ops"));

    mvc.perform(get("/api/v1/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].skill.name").value("weather"))
        .andExpect(jsonPath("$.data[0].agentNames[0]").value("ops"));
  }

  @Test
  void updatesContentAndReturnsEditableDetail() throws Exception {
    when(service.get("weather")).thenReturn(skill());
    when(service.getContent("weather")).thenReturn("updated");
    when(service.associatedAgents("weather")).thenReturn(List.of("ops"));
    when(service.availableAgents()).thenReturn(List.of("ops", "reporter"));

    mvc.perform(
            put("/api/v1/skills/weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content").value("updated"))
        .andExpect(jsonPath("$.data.availableAgents[1]").value("reporter"));

    verify(service).updateContent("weather", "updated");
  }

  private static SkillDescriptor skill() {
    SkillMetadata metadata =
        new SkillMetadata(
            "weather",
            "Weather guidance",
            null,
            null,
            Map.of(),
            null,
            Path.of("/workspace/.oryxos/skills/weather/SKILL.md"),
            "skills/weather/SKILL.md");
    return new SkillDescriptor(
        "global",
        "weather",
        metadata,
        SkillStatus.ENABLED,
        true,
        SkillSource.UPLOAD,
        Instant.parse("2026-07-23T00:00:00Z"),
        null,
        "skills/weather/SKILL.md",
        List.of("SKILL.md"),
        1,
        64,
        true);
  }
}
