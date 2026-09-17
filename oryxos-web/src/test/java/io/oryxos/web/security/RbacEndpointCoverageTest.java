package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.web.config.WebRbacProperties;
import io.oryxos.web.security.RbacEndpointCoverage.MappedEndpoint;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class RbacEndpointCoverageTest {

  @Test
  void knownProtectedRoutesAreCovered() {
    List<MappedEndpoint> routes =
        List.of(
            new MappedEndpoint("GET", "/api/v1/health"),
            new MappedEndpoint("GET", "/api/v1/info"),
            new MappedEndpoint("GET", "/api/v1/auth/me"),
            new MappedEndpoint("POST", "/api/v1"),
            new MappedEndpoint("POST", "/api/v1/"),
            new MappedEndpoint("GET", "/api/v1/channels/inbound/{name}"),
            new MappedEndpoint("GET", "/api/v1/agents/{name}/governance"),
            new MappedEndpoint("PUT", "/api/v1/skills/{name}/governance"),
            new MappedEndpoint("PATCH", "/api/v1/knowledge/{name}"),
            new MappedEndpoint("POST", "/api/v1/agents/{name}/invoke"),
            new MappedEndpoint("POST", "/api/v2/agents/{profile}/schedules/{key}/run"),
            new MappedEndpoint("GET", "/api/v2/schedules/{scheduleId}/executions"),
            new MappedEndpoint("GET", "/actuator/prometheus"),
            new MappedEndpoint("GET", "/admin/"));

    assertThat(RbacEndpointCoverage.gaps(routes)).isEmpty();
  }

  @Test
  void unmappedProtectedPathIsReported() {
    List<MappedEndpoint> routes = List.of(new MappedEndpoint("GET", "/api/v1/not-registered"));

    assertThat(RbacEndpointCoverage.gaps(routes)).contains("GET /api/v1/not-registered");
  }

  @Test
  void pathVariableIsSubstitutedBeforeResolve() {
    assertThat(RbacEndpointCoverage.concrete("/api/v1/agents/{name:.+}/skills/{skill}"))
        .isEqualTo("/api/v1/agents/x/skills/x");
  }

  @Test
  void rbacOffDoesNotReadMappings() {
    WebRbacProperties rbac = new WebRbacProperties();
    rbac.setEnabled(false);
    RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
    RbacEndpointCoverageCheck check = new RbacEndpointCoverageCheck(rbac, mapping);

    check.run(null);

    verify(mapping, never()).getHandlerMethods();
  }

  @Test
  void rbacOnFailsWhenHandlerUnmapped() {
    WebRbacProperties rbac = new WebRbacProperties();
    rbac.setEnabled(true);
    RequestMappingInfo info =
        RequestMappingInfo.paths("/api/v1/not-registered").methods(RequestMethod.GET).build();
    RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
    when(mapping.getHandlerMethods()).thenReturn(Map.of(info, mock(HandlerMethod.class)));
    RbacEndpointCoverageCheck check = new RbacEndpointCoverageCheck(rbac, mapping);

    assertThatThrownBy(() -> check.run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GET /api/v1/not-registered");
  }
}
