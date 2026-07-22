package io.oryxos.web.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = AdminAuthFailureTest.TestApplication.class,
    properties = {
      "oryxos.admin.username=admin",
      "oryxos.admin.password-hash={noop}secret",
      "oryxos.admin.secure-cookie=false"
    })
@AutoConfigureMockMvc
class AdminAuthFailureTest {

  @Autowired MockMvc mvc;

  @Autowired LoginFailureTracker failureTracker;

  @BeforeEach
  void resetState() {
    failureTracker.clear("admin");
    failureTracker.clear("missing");
  }

  @Test
  void unknownUsernameAndWrongPasswordUseSameGenericResponseAndNoSession() throws Exception {
    expectInvalid("{\"username\":\"missing\",\"password\":\"secret\"}");
    expectInvalid("{\"username\":\"admin\",\"password\":\"wrong\"}");
  }

  private void expectInvalid(String body) throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Invalid credentials"))
        .andExpect(cookie().doesNotExist("JSESSIONID"));
  }

  @SpringBootApplication
  @Import({AdminSecurityConfig.class, AuthApiController.class, GlobalExceptionHandler.class})
  static class TestApplication {

    @Bean("failureClock")
    @Primary
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-07-22T09:00:00Z"), ZoneOffset.UTC);
    }

    @Bean("failureLoginFailureTracker")
    @Primary
    LoginFailureTracker loginFailureTracker(Clock clock) {
      return new LoginFailureTracker(clock);
    }

    @Bean("failureLocalAdminIdentityService")
    @Primary
    LocalAdminIdentityService localAdminIdentityService(AdminSecurityProperties properties) {
      return new LocalAdminIdentityService(
          properties, PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    @Bean
    InMemoryAuditStore adminAuthAuditStore() {
      return new InMemoryAuditStore();
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
