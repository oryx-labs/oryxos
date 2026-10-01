package io.oryxos.web.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.oryxos.core.a2a.A2aHopContext;
import io.oryxos.core.a2a.A2aProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class A2aHopFilterTest {

  private static A2aProperties props(int maxHops) {
    return new A2aProperties(
        true, "OryxOS", "d", "http://localhost:8080", "0.1.6", "0.3.0", "", "", 30, "", maxHops);
  }

  @Test
  @DisplayName("hop within limit sets context")
  void withinLimit() throws Exception {
    A2aHopFilter filter = new A2aHopFilter(props(3));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", A2aAuthFilter.A2A_PATH);
    req.addHeader(A2aHopContext.HOP_HEADER, "2");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    AtomicInteger seen = new AtomicInteger(-1);
    filter.doFilter(
        req,
        resp,
        (r, s) -> {
          seen.set(A2aHopContext.current());
        });
    assertEquals(200, resp.getStatus());
    assertEquals(2, seen.get());
    assertEquals(0, A2aHopContext.current());
  }

  @Test
  @DisplayName("path parameters do not skip the hop limit")
  void pathParametersDoNotSkipTheLimit() throws Exception {
    A2aHopFilter filter = new A2aHopFilter(props(3));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/a2a;x=1");
    req.addHeader(A2aHopContext.HOP_HEADER, "9");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    AtomicBoolean continued = new AtomicBoolean();
    filter.doFilter(req, resp, (r, s2) -> continued.set(true));
    assertFalse(continued.get(), "over-limit hop must not reach the chain");
    assertEquals(403, resp.getStatus());
  }

  @Test
  @DisplayName("hop over max → 403")
  void overLimit() throws Exception {
    A2aHopFilter filter = new A2aHopFilter(props(3));
    MockHttpServletRequest req = new MockHttpServletRequest("POST", A2aAuthFilter.A2A_PATH);
    req.addHeader(A2aHopContext.HOP_HEADER, "4");
    MockHttpServletResponse resp = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(req, resp, chain);
    assertEquals(403, resp.getStatus());
    assertTrue(resp.getContentAsString().contains("hop limit"));
  }
}
