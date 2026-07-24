package io.oryxos.boot;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.controller.AgentSkillApiController;
import io.oryxos.web.controller.SkillApiController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Public upload -> canonical association -> next-request discovery proof. */
class SkillManagementE2ETest {

  private static final String BODY = "BODY_MUST_REMAIN_L2";

  @TempDir Path root;
  private SkillMarketTestSupport.Market market;
  private MockMvc mvc;

  @BeforeEach
  void setUp() throws Exception {
    market = SkillMarketTestSupport.create(root, "ops");
    mvc =
        MockMvcBuilders.standaloneSetup(
                new SkillApiController(market.management()),
                new AgentSkillApiController(market.associationManager(), market.catalog()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void uploadAssociateAndDiscoverWithoutRestart() throws Exception {
    mvc.perform(
            multipart("/api/v1/skills")
                .file(
                    new MockMultipartFile(
                        "file",
                        "weather.zip",
                        "application/zip",
                        SkillMarketTestSupport.zip("weather", BODY))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("weather"))
        .andExpect(content().string(not(containsString(BODY))));

    mvc.perform(put("/api/v1/agents/ops/skills/weather"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.target").value("../../../skills/weather"));
    mvc.perform(get("/api/v1/agents/ops/skills"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].discoverable").value(true));

    assertTrue(Files.isSymbolicLink(root.resolve("agents/ops/skills/weather")));
    assertEquals(
        List.of("weather"),
        market.agentCatalog().snapshot("ops").skills().stream()
            .map(skill -> skill.name())
            .toList());
  }

  @Test
  void rejectedAndDuplicateUploadsLeaveNoStagingOrPublishedPartialPackage() throws Exception {
    MockMultipartFile invalid =
        new MockMultipartFile("file", "broken.zip", "application/zip", new byte[] {1, 2, 3});
    mvc.perform(multipart("/api/v1/skills").file(invalid)).andExpect(status().isBadRequest());
    assertFalse(Files.exists(root.resolve("skills/broken")));

    byte[] valid = SkillMarketTestSupport.zip("weather", BODY);
    mvc.perform(
            multipart("/api/v1/skills")
                .file(new MockMultipartFile("file", "weather.zip", "application/zip", valid)))
        .andExpect(status().isOk());
    mvc.perform(
            multipart("/api/v1/skills")
                .file(new MockMultipartFile("file", "again.zip", "application/zip", valid)))
        .andExpect(status().isConflict());

    try (var events = Files.list(root.resolve(".staging/skill-import"))) {
      assertEquals(0, events.count());
    }
    assertEquals(
        List.of("weather"), market.catalog().list().stream().map(skill -> skill.name()).toList());
  }
}
