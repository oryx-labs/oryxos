package io.oryxos.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.auth.AdminAuthAuditStore;
import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.core.auth.AdminAuthEventType;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = OryxOsRuntime.class)
@AutoConfigureMockMvc
class AdminLoginFlowIT {

  private static final Path ROOT = createWorkspace();

  @Autowired MockMvc mvc;

  @Autowired AdminAuthAuditStore auditStore;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("oryxos.root", ROOT::toString);
    registry.add("oryxos.providers[0].name", () -> "mock");
    registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + ROOT.resolve("oryxos.db"));
    registry.add("spring.sql.init.mode", () -> "always");
    registry.add("oryxos.admin.username", () -> "admin");
    registry.add("oryxos.admin.password-hash", () -> "{noop}secret");
    registry.add("oryxos.admin.secure-cookie", () -> "false");
  }

  private static Path createWorkspace() {
    try {
      Path root = Files.createTempDirectory("oryxos-admin-login");
      Files.createDirectories(root.resolve("agents"));
      return root;
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Test
  void configuredAdminCanLoginAccessProtectedApiAndLogout() throws Exception {
    mvc.perform(get("/api/v1/info")).andExpect(status().isUnauthorized());

    MvcResult login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("admin"))
            .andReturn();

    Cookie cookie = login.getResponse().getCookie("JSESSIONID");
    MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(get("/api/v1/info").session(session).cookie(cookie)).andExpect(status().isOk());

    mvc.perform(post("/api/v1/auth/logout").with(csrf()).session(session).cookie(cookie))
        .andExpect(status().isOk())
        .andExpect(cookie().maxAge("JSESSIONID", 0));

    mvc.perform(get("/api/v1/info").cookie(cookie)).andExpect(status().isUnauthorized());

    assertThat(auditStore.findRecent(10).stream().map(AdminAuthEvent::eventType))
        .contains(AdminAuthEventType.LOGIN_SUCCEEDED, AdminAuthEventType.LOGOUT);
  }

  @Test
  void healthRemainsPublicAndDocumentationRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
  }
}
