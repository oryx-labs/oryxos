package io.oryxos.web.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.a2a.A2aHopContext;
import io.oryxos.core.a2a.A2aProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces {@code X-A2A-Hop} budget on {@code POST /api/v1/a2a} and publishes the inbound hop into
 * {@link A2aHopContext} for outbound client increments.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "A2aProperties is a Spring-injected shared singleton.")
public final class A2aHopFilter extends OncePerRequestFilter {

  private final A2aProperties properties;

  public A2aHopFilter(A2aProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!A2aAuthFilter.A2A_PATH.equals(A2aAuthFilter.guardedPath(request))) {
      filterChain.doFilter(request, response);
      return;
    }
    int hop = parseHop(request.getHeader(A2aHopContext.HOP_HEADER));
    if (hop > properties.maxHops()) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getWriter()
          .write(
              "{\"error\":\"A2A hop limit exceeded\",\"hop\":"
                  + hop
                  + ",\"max\":"
                  + properties.maxHops()
                  + "}");
      return;
    }
    A2aHopContext.set(hop);
    try {
      filterChain.doFilter(request, response);
    } finally {
      A2aHopContext.clear();
    }
  }

  static int parseHop(String raw) {
    if (raw == null || raw.isBlank()) {
      return 0;
    }
    try {
      return Math.max(0, Integer.parseInt(raw.strip()));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
