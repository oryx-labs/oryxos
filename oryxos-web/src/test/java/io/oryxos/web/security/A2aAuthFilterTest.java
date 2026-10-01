package io.oryxos.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.a2a.A2aProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class A2aAuthFilterTest {

  private static A2aProperties withToken(String token) {
    return new A2aProperties(
        true, "OryxOS", "d", "http://localhost:8080", "0.1.6", "0.3.0", "", "", 30, token, 3);
  }

  @Test
  @DisplayName("no shared token → pass through")
  void noToken_passthrough() throws Exception {
    A2aAuthFilter filter = new A2aAuthFilter(A2aProperties.disabled());
    MockHttpServletRequest req = new MockHttpServletRequest("POST", A2aAuthFilter.A2A_PATH);
    MockHttpServletResponse resp = new MockHttpServletResponse();
    AtomicBoolean continued = new AtomicBoolean();
    filter.doFilter(req, resp, (r, s) -> continued.set(true));
    assertTrue(continued.get());
    assertEquals(200, resp.getStatus());
  }

  @Test
  @DisplayName("valid Bearer accepted")
  void validBearer() throws Exception {
    A2aAuthFilter filter = new A2aAuthFilter(withToken("s3cret"));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", A2aAuthFilter.A2A_PATH);
    req.addHeader("Authorization", "Bearer s3cret");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, resp, chain);
    assertEquals(200, resp.getStatus());
  }

  @Test
  @DisplayName("missing token → 401")
  void missingToken() throws Exception {
    A2aAuthFilter filter = new A2aAuthFilter(withToken("s3cret"));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", A2aAuthFilter.A2A_PATH);
    MockHttpServletResponse resp = new MockHttpServletResponse();
    AtomicBoolean continued = new AtomicBoolean();
    filter.doFilter(req, resp, (r, s) -> continued.set(true));
    assertFalse(continued.get());
    assertEquals(401, resp.getStatus());
  }

  @Test
  @DisplayName("path parameters do not skip the shared token (servlet mapping strips them)")
  void pathParametersDoNotSkipTheGate() throws Exception {
    // The container maps /api/v1/a2a;x=1 to this filter, and Spring MVC routes it to the handler,
    // so the gate has to see the same path the container matched. Comparing getRequestURI() let
    // every ;suffix through with no token checked.
    A2aAuthFilter filter = new A2aAuthFilter(withToken("s3cret"));
    for (String uri :
        new String[] {"/api/v1/a2a;x=1", "/api/v1/a2a;", "/api/v1/a2a;jsessionid=AB"}) {
      MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
      MockHttpServletResponse resp = new MockHttpServletResponse();
      AtomicBoolean continued = new AtomicBoolean();
      filter.doFilter(req, resp, (r, s2) -> continued.set(true));
      assertFalse(continued.get(), uri + " must not reach the chain without a token");
      assertEquals(401, resp.getStatus(), uri);
    }
  }

  @Test
  @DisplayName("a valid token still passes when the path carries parameters")
  void pathParametersWithValidToken() throws Exception {
    A2aAuthFilter filter = new A2aAuthFilter(withToken("s3cret"));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/a2a;x=1");
    req.addHeader("Authorization", "Bearer s3cret");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, resp, chain);
    assertEquals(200, resp.getStatus());
  }

  @Test
  @DisplayName("X-A2A-Token accepted")
  void xA2aToken() throws Exception {
    A2aAuthFilter filter = new A2aAuthFilter(withToken("s3cret"));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", A2aAuthFilter.A2A_PATH);
    req.addHeader("X-A2A-Token", "s3cret");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, resp, chain);
    assertEquals(200, resp.getStatus());
  }

  @Test
  @DisplayName("matchesSharedToken constant-time helper")
  void matchesHelper() {
    A2aProperties props = withToken("abc");
    assertTrue(props.matchesSharedToken("abc"));
    assertFalse(props.matchesSharedToken("abd"));
    assertFalse(props.matchesSharedToken(null));
  }
}
