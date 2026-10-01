package io.oryxos.web.security;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.a2a.A2aProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Optional A2A shared-bearer gate for {@code POST /api/v1/a2a}. Active only when {@link
 * A2aProperties#hasSharedToken()}. Agent Card discovery stays public. When active, ApiKeyAuthFilter
 * exempts this path so peers need only the shared token.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "A2aProperties is a Spring-injected shared singleton.")
public final class A2aAuthFilter extends OncePerRequestFilter {

  public static final String A2A_PATH = "/api/v1/a2a";
  private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;
  private static final String BEARER_PREFIX = "Bearer ";
  private static final String A2A_TOKEN_HEADER = "X-A2A-Token";

  private final A2aProperties properties;

  public A2aAuthFilter(A2aProperties properties) {
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (!properties.hasSharedToken()) {
      filterChain.doFilter(request, response);
      return;
    }
    if (HttpMethod.OPTIONS.matches(request.getMethod())) {
      filterChain.doFilter(request, response);
      return;
    }
    if (!A2A_PATH.equals(guardedPath(request))) {
      filterChain.doFilter(request, response);
      return;
    }
    String presented = extractToken(request);
    if (properties.matchesSharedToken(presented)) {
      filterChain.doFilter(request, response);
      return;
    }
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"OryxOS-A2A\"");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write("{\"error\":\"Unauthorized\"}");
  }

  /**
   * The request path as the servlet container maps it: path parameters ({@code ;name=value}) and
   * context path removed. {@link HttpServletRequest#getRequestURI()} returns the raw request target
   * instead, so comparing it with {@link #A2A_PATH} let {@code /api/v1/a2a;x=1} reach the handler
   * with neither the shared token nor the hop limit checked.
   */
  static String guardedPath(HttpServletRequest request) {
    return PATH_HELPER.getPathWithinApplication(request);
  }

  static String extractToken(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
      String key = authorization.substring(BEARER_PREFIX.length()).strip();
      if (!key.isEmpty()) {
        return key;
      }
    }
    String alt = request.getHeader(A2A_TOKEN_HEADER);
    if (alt != null && !alt.isBlank()) {
      return alt.strip();
    }
    return null;
  }
}
