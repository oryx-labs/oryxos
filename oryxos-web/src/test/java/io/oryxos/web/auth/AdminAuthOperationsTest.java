package io.oryxos.web.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.auth.AdminAuthAuditStore;
import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.core.auth.AdminAuthEventType;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.AdminSecurityConfig;
import io.oryxos.web.config.AdminSecurityProperties;
import io.oryxos.web.controller.AuthApiController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    classes = AdminAuthOperationsTest.TestApplication.class,
    properties = {
      "oryxos.admin.username=admin",
      "oryxos.admin.password-hash={noop}secret",
      "oryxos.admin.secure-cookie=false"
    })
@AutoConfigureMockMvc
class AdminAuthOperationsTest {

  @Autowired MockMvc mvc;

  @Autowired InMemoryAuditStore auditStore;

  @BeforeEach
  void resetState() {
    auditStore.events.clear();
  }

  @Test
  void authEventsRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/auth/events")).andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(username = "admin", roles = "ADMIN")
  void returnsSanitizedNewestFirstEventsAndValidatesLimit() throws Exception {
    auditStore.record(
        new AdminAuthEvent(
            "old-admin",
            AdminAuthEventType.LOGIN_SUCCEEDED,
            Instant.parse("2026-07-22T08:00:00Z"),
            "127.0.0.1",
            "browser",
            "s1"));
    auditStore.record(
        new AdminAuthEvent(
            "admin\r\nspoof",
            AdminAuthEventType.LOGIN_FAILED,
            Instant.parse("2026-07-22T09:00:00Z"),
            "10.0.0.1",
            "evil\nagent",
            "s2"));

    mvc.perform(get("/api/v1/auth/events?limit=1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].principal").value("admin__spoof"))
        .andExpect(jsonPath("$.data[0].eventType").value("LOGIN_FAILED"))
        .andExpect(jsonPath("$.data[0].userAgent").value("evil_agent"));

    mvc.perform(get("/api/v1/auth/events?limit=0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @SpringBootApplication
  @Import({AdminSecurityConfig.class, AuthApiController.class, GlobalExceptionHandler.class})
  static class TestApplication {

    @Bean("operationsClock")
    @Primary
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-07-22T09:00:00Z"), ZoneOffset.UTC);
    }

    @Bean("operationsLocalAdminIdentityService")
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
      return events.stream()
          .sorted(Comparator.comparing(AdminAuthEvent::occurredAt).reversed())
          .limit(limit)
          .toList();
    }
  }
}
