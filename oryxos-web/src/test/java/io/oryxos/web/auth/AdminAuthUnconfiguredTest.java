package io.oryxos.web.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.auth.AdminAuthAuditStore;
import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.AdminSecurityConfig;
import io.oryxos.web.controller.AuthApiController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = AdminAuthUnconfiguredTest.TestApplication.class,
    properties = {
      "oryxos.admin.username=",
      "oryxos.admin.password-hash=",
      "oryxos.admin.secure-cookie=false"
    })
@AutoConfigureMockMvc
class AdminAuthUnconfiguredTest {

  @Autowired MockMvc mvc;

  @Test
  void unconfiguredDeploymentStaysClosed() throws Exception {
    mvc.perform(get("/api/v1/auth/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.configured").value(false))
        .andExpect(jsonPath("$.data.authenticated").value(false));

    mvc.perform(get("/api/v1/info"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }

  @SpringBootApplication
  @Import({AdminSecurityConfig.class, AuthApiController.class, GlobalExceptionHandler.class})
  static class TestApplication {

    @Bean
    AdminAuthAuditStore adminAuthAuditStore() {
      return new AdminAuthAuditStore() {
        @Override
        public void record(AdminAuthEvent event) {}

        @Override
        public List<AdminAuthEvent> findRecent(int limit) {
          return List.of();
        }
      };
    }

    @RestController
    static class ProtectedController {
      @GetMapping("/api/v1/info")
      Object info() {
        return io.oryxos.web.common.ApiResponse.ok(java.util.Map.of("status", "ok"));
      }
    }
  }
}
