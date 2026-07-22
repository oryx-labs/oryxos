package io.oryxos.web.controller;

import io.oryxos.core.auth.AdminAuthAuditStore;
import io.oryxos.core.auth.AdminAuthEvent;
import io.oryxos.core.auth.AdminAuthEventType;
import io.oryxos.web.auth.AdminAuthException;
import io.oryxos.web.auth.LocalAdminIdentityService;
import io.oryxos.web.auth.LoginFailureTracker;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.config.AdminSecurityProperties;
import io.oryxos.web.controller.dto.AdminAuthEventView;
import io.oryxos.web.controller.dto.AuthStatusView;
import io.oryxos.web.controller.dto.CsrfTokenView;
import io.oryxos.web.controller.dto.LoginRequest;
import io.oryxos.web.controller.dto.LoginView;
import io.oryxos.web.controller.dto.LogoutView;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authentication API for the same-origin OryxOS management console. */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "SPRING_ENDPOINT: auth endpoints are intentionally exposed as the same-origin login surface. "
            + "EI_EXPOSE_REP2: collaborators are Spring-managed singleton services/properties; "
            + "constructor injection shares those references by design.")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthApiController {

  private static final int DEFAULT_EVENT_LIMIT = 50;
  private static final int MAX_EVENT_LIMIT = 100;

  private final LocalAdminIdentityService identityService;
  private final LoginFailureTracker failureTracker;
  private final AdminAuthAuditStore auditStore;
  private final SecurityContextRepository securityContextRepository;
  private final Clock clock;
  private final AdminSecurityProperties properties;

  public AuthApiController(
      LocalAdminIdentityService identityService,
      LoginFailureTracker failureTracker,
      AdminAuthAuditStore auditStore,
      SecurityContextRepository securityContextRepository,
      Clock clock,
      AdminSecurityProperties properties) {
    this.identityService = identityService;
    this.failureTracker = failureTracker;
    this.auditStore = auditStore;
    this.securityContextRepository = securityContextRepository;
    this.clock = clock;
    this.properties = properties;
  }

  @GetMapping("/status")
  public ApiResponse<AuthStatusView> status(Authentication authentication) {
    boolean authenticated =
        authentication != null
            && authentication.isAuthenticated()
            && !(authentication instanceof AnonymousAuthenticationToken);
    String username = authenticated ? authentication.getName() : null;
    return ApiResponse.ok(
        new AuthStatusView(identityService.configured(), authenticated, username));
  }

  @GetMapping("/csrf")
  public ResponseEntity<ApiResponse<CsrfTokenView>> csrf(
      org.springframework.security.web.csrf.CsrfToken token) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.ok(new CsrfTokenView(token.getHeaderName(), token.getToken())));
  }

  @PostMapping("/login")
  public ApiResponse<LoginView> login(
      @RequestBody LoginRequest request,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    requireConfigured();
    String submittedPassword = request == null ? null : request.password();
    String submittedUsername = request == null ? null : request.username();
    LoginFailureTracker.Result current = failureTracker.current(submittedUsername);
    if (current.locked()) {
      audit(servletRequest, AdminAuthEventType.LOGIN_LOCKED, principal(submittedUsername), null);
      throw AdminAuthException.locked();
    }
    if (!identityService.matches(submittedUsername, submittedPassword)) {
      LoginFailureTracker.Result afterFailure = failureTracker.recordFailure(submittedUsername);
      AdminAuthEventType eventType =
          afterFailure.locked() ? AdminAuthEventType.LOGIN_LOCKED : AdminAuthEventType.LOGIN_FAILED;
      audit(servletRequest, eventType, principal(submittedUsername), null);
      throw AdminAuthException.unauthorized();
    }
    failureTracker.clear(submittedUsername);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    Authentication authentication =
        new UsernamePasswordAuthenticationToken(
            identityService.username(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    HttpSession session = servletRequest.getSession(true);
    servletRequest.changeSessionId();
    addSessionCookie(servletRequest, servletResponse, session.getId(), -1);
    audit(
        servletRequest,
        AdminAuthEventType.LOGIN_SUCCEEDED,
        identityService.username(),
        session.getId());
    securityContextRepository.saveContext(context, servletRequest, servletResponse);
    return ApiResponse.ok(new LoginView(identityService.username()));
  }

  @PostMapping("/logout")
  public ApiResponse<LogoutView> logout(
      Authentication authentication,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {
    String principal =
        authentication == null ? identityService.username() : authentication.getName();
    HttpSession session = servletRequest.getSession(false);
    String sessionId = session == null ? null : session.getId();
    audit(servletRequest, AdminAuthEventType.LOGOUT, principal, sessionId);
    SecurityContextHolder.clearContext();
    if (session != null) {
      session.invalidate();
    }
    addSessionCookie(servletRequest, servletResponse, "", 0);
    return ApiResponse.ok(new LogoutView(true));
  }

  private void addSessionCookie(
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse,
      String value,
      int maxAge) {
    Cookie cookie = new Cookie("JSESSIONID", value);
    cookie.setHttpOnly(true);
    String contextPath = servletRequest.getContextPath();
    cookie.setPath(contextPath.isBlank() ? "/" : contextPath);
    cookie.setSecure(properties.isSecureCookie());
    cookie.setMaxAge(maxAge);
    servletResponse.addCookie(cookie);
  }

  @GetMapping("/events")
  public ApiResponse<List<AdminAuthEventView>> events(
      @RequestParam(defaultValue = "" + DEFAULT_EVENT_LIMIT) int limit) {
    if (limit < 1 || limit > MAX_EVENT_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_EVENT_LIMIT);
    }
    List<AdminAuthEventView> events =
        auditStore.findRecent(limit).stream().map(AuthApiController::toView).toList();
    return ApiResponse.ok(events);
  }

  private void requireConfigured() {
    if (!identityService.configured()) {
      throw AdminAuthException.unconfigured();
    }
  }

  private void audit(
      HttpServletRequest request,
      AdminAuthEventType eventType,
      String principal,
      String sessionId) {
    auditStore.record(
        new AdminAuthEvent(
            principal,
            eventType,
            clock.instant(),
            request.getRemoteAddr(),
            request.getHeader("User-Agent"),
            sessionId));
  }

  private static AdminAuthEventView toView(AdminAuthEvent event) {
    return new AdminAuthEventView(
        sanitize(event.principal()),
        event.eventType().name(),
        event.occurredAt(),
        sanitize(event.remoteAddress()),
        sanitize(event.userAgent()));
  }

  private static String principal(String username) {
    return username == null || username.isBlank() ? "<unknown>" : username.trim();
  }

  private static String sanitize(String value) {
    return value == null ? null : value.replace('\r', '_').replace('\n', '_');
  }
}
