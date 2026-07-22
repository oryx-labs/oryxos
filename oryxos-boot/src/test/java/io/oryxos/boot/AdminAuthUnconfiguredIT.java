package io.oryxos.boot;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.cli.OryxOsRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = OryxOsRuntime.class)
@AutoConfigureMockMvc
class AdminAuthUnconfiguredIT {

  private static final Path ROOT = createWorkspace();

  @Autowired MockMvc mvc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("oryxos.providers[0].name", () -> "mock");
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
    registry.add("spring.sql.init.mode", () -> "always");
    registry.add("oryxos.admin.username", () -> "");
    registry.add("oryxos.admin.password-hash", () -> "");
    registry.add("oryxos.admin.secure-cookie", () -> "false");
  }

  private static Path createWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-admin-unconfigured");
      Files.createDirectories(root.resolve("agents"));
      return root;
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Test
  void unconfiguredDeploymentDoesNotExposeManagementApi() throws Exception {
    mvc.perform(get("/api/v1/auth/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.configured").value(false));

    mvc.perform(get("/api/v1/info")).andExpect(status().isUnauthorized());

    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }
}
