package io.oryxos.web.auth;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.auth.AdminAuthAuditStore;
import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.AdminSecurityConfig;
import io.oryxos.web.config.AdminSecurityProperties;
import io.oryxos.web.controller.AuthApiController;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
    classes = AdminAuthSecurityTest.TestApplication.class,
    properties = {
      "oryxos.admin.username=admin",
      "oryxos.admin.password-hash={noop}secret",
      "oryxos.admin.secure-cookie=false"
    })
@AutoConfigureMockMvc
class AdminAuthSecurityTest {

  @Autowired MockMvc mvc;

  @Autowired InMemoryAuditStore auditStore;

  @Autowired LoginFailureTracker failureTracker;

  @BeforeEach
  void resetState() {
    auditStore.events.clear();
    failureTracker.clear("admin");
  }

  @Test
  void protectsManagementApiAndAllowsLoginThenLogout() throws Exception {
    mvc.perform(get("/api/v1/info"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    mvc.perform(get("/api/v1/auth/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.configured").value(true))
        .andExpect(jsonPath("$.data.authenticated").value(false));

    MvcResult login =
        mvc.perform(
                post("/api/v1/auth/login")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.username").value("admin"))
            .andReturn();

    Cookie session = login.getResponse().getCookie("JSESSIONID");
    MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
    mvc.perform(get("/api/v1/info").session(authenticatedSession).cookie(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ok"));

    mvc.perform(
            post("/api/v1/auth/logout").with(csrf()).session(authenticatedSession).cookie(session))
        .andExpect(status().isOk())
        .andExpect(cookie().maxAge("JSESSIONID", 0));

    mvc.perform(get("/api/v1/info").cookie(session)).andExpect(status().isUnauthorized());
    org.assertj.core.api.Assertions.assertThat(auditStore.events)
        .extracting(AdminAuthEvent::eventType)
        .extracting(Enum::name)
        .contains("LOGIN_SUCCEEDED", "LOGOUT");
  }

  @Test
  void rejectsInvalidCredentialsWithGenericMessageAndLocksAfterFiveFailures() throws Exception {
    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/api/v1/auth/login")
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.message").value(containsString("Invalid credentials")));
    }

    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"secret\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429));
  }

  @SpringBootApplication
  @Import({AdminSecurityConfig.class, AuthApiController.class, GlobalExceptionHandler.class})
  static class TestApplication {

    @Bean("testClock")
    @Primary
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-07-22T09:00:00Z"), ZoneOffset.UTC);
    }

    @Bean("testLoginFailureTracker")
    @Primary
    LoginFailureTracker loginFailureTracker(Clock clock) {
      return new LoginFailureTracker(clock);
    }

    @Bean("testLocalAdminIdentityService")
    @Primary
    LocalAdminIdentityService localAdminIdentityService(AdminSecurityProperties properties) {
      return new LocalAdminIdentityService(
          properties, PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    @Bean
    InMemoryAuditStore adminAuthAuditStore() {
      return new InMemoryAuditStore();
    }

    @RestController
    static class ProtectedController {
      @GetMapping("/api/v1/info")
      Object info() {
        return io.oryxos.web.common.ApiResponse.ok(java.util.Map.of("status", "ok"));
      }
    }
  }

  static class InMemoryAuditStore implements AdminAuthAuditStore {
    private final List<AdminAuthEvent> events = new ArrayList<>();

    @Override
    public void record(AdminAuthEvent event) {
      events.add(event);
    }

    @Override
    public List<AdminAuthEvent> findRecent(int limit) {
      return events.reversed().stream().limit(limit).toList();
    }
  }
}
