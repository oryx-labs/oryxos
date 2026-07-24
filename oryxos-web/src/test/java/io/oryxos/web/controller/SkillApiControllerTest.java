package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.skill.DeleteResult;
import io.oryxos.core.skill.PublicSkillDescriptor;
import io.oryxos.core.skill.PublicSkillManagementService;
import io.oryxos.core.skill.SkillInUseException;
import io.oryxos.core.skill.SkillMetadata;
import io.oryxos.core.skill.SkillSource;
import io.oryxos.core.skill.SkillStatus;
import io.oryxos.web.GlobalExceptionHandler;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillApiControllerTest {

  private PublicSkillManagementService skills;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    skills = mock(PublicSkillManagementService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new SkillApiController(skills))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void listsAndGetsOnlySafePublicMetadata() throws Exception {
    PublicSkillDescriptor weather = descriptor("weather", List.of("ops"));
    when(skills.list()).thenReturn(List.of(weather));
    when(skills.get("weather")).thenReturn(weather);

    mvc.perform(get("/api/v1/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].name").value("weather"))
        .andExpect(jsonPath("$.data[0].linkedAgents[0]").value("ops"))
        .andExpect(jsonPath("$.data[0].promptContent").doesNotExist());
    mvc.perform(get("/api/v1/skills/weather"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.entrypoint").value("skills/weather/SKILL.md"));
  }

  @Test
  void importsOneMultipartZipThroughTheAuthoritativePipeline() throws Exception {
    when(skills.importZip(any(InputStream.class), eq("weather.zip")))
        .thenReturn(descriptor("weather", List.of()));
    MockMultipartFile archive =
        new MockMultipartFile("file", "weather.zip", "application/zip", new byte[] {1, 2, 3});

    mvc.perform(multipart("/api/v1/skills").file(archive))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("weather"));
    verify(skills).importZip(any(InputStream.class), eq("weather.zip"));
  }

  @Test
  void rejectsMissingMultipartFile() throws Exception {
    mvc.perform(multipart("/api/v1/skills"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void changesGlobalEnabledStateUsingAStrictBoolean() throws Exception {
    when(skills.setEnabled("weather", false)).thenReturn(descriptor("weather", List.of()));

    mvc.perform(
            put("/api/v1/skills/weather")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"))
        .andExpect(status().isOk());
    verify(skills).setEnabled("weather", false);
  }

  @Test
  void normalDeleteReturnsTyped409AndForceIsASeparateRequest() throws Exception {
    when(skills.delete("weather", false))
        .thenThrow(new SkillInUseException("weather", List.of("ops", "finance")));
    when(skills.delete("weather", true))
        .thenReturn(new DeleteResult("weather", true, List.of("ops", "finance"), true));

    mvc.perform(delete("/api/v1/skills/weather"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.data.reasonCode").value("SKILL_IN_USE"))
        .andExpect(jsonPath("$.data.linkedAgents[0]").value("finance"))
        .andExpect(jsonPath("$.data.linkedAgents[1]").value("ops"));

    mvc.perform(delete("/api/v1/skills/weather").queryParam("force", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.forced").value(true));
  }

  private static PublicSkillDescriptor descriptor(String name, List<String> linkedAgents) {
    Path entry = Path.of("/workspace/skills", name, "SKILL.md");
    SkillMetadata metadata =
        new SkillMetadata(
            name, "description", null, null, Map.of(), null, entry, "skills/" + name + "/SKILL.md");
    return new PublicSkillDescriptor(
        name,
        metadata,
        SkillStatus.ENABLED,
        true,
        SkillSource.UPLOAD,
        Instant.EPOCH,
        null,
        "skills/" + name + "/SKILL.md",
        List.of("SKILL.md"),
        1,
        100,
        linkedAgents);
  }
}
