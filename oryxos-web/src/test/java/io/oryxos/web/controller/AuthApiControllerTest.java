package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.GlobalExceptionHandler;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 012-web-auth US3 验收 harness：AuthApiControllerTest——login/logout/me 端点钉死。 standalone MockMvc +
 * mock WebUserService/WebSessionService，不碰 DB。
 */
class AuthApiControllerTest {

  private WebUserService userService;
  private WebSessionService sessionService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    userService = mock(WebUserService.class);
    sessionService = mock(WebSessionService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new AuthApiController(userService, sessionService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  @DisplayName("login_HTTP对账密_200+Set-Cookie(HttpOnly+SameSite=Strict+Path=/且无Secure)")
  void login_correctCredentials_setsCookie() throws Exception {
    when(userService.verify("admin", "s3cret-pw")).thenReturn(true);
    WebSession session = newSession("admin", "sid-123");
    when(sessionService.create("admin")).thenReturn(session);

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"s3cret-pw\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.username").value("admin"))
        .andExpect(header().exists("Set-Cookie"))
        .andExpect(
            header()
                .string(
                    "Set-Cookie", org.hamcrest.Matchers.containsString("oryxos_session=sid-123")))
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
        .andExpect(
            header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")))
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Path=/")))
        .andExpect(
            header()
                .string(
                    "Set-Cookie",
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Secure"))));
  }

  @Test
  @DisplayName("login_HTTPS对账密_Set-Cookie包含Secure")
  void login_https_setsSecureCookie() throws Exception {
    when(userService.verify("admin", "s3cret-pw")).thenReturn(true);
    WebSession session = newSession("admin", "sid-123");
    when(sessionService.create("admin")).thenReturn(session);

    mvc.perform(
            post("/api/v1/auth/login")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"s3cret-pw\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")));
  }

  @Test
  @DisplayName("login_错账密_401+不区分原因（防枚举）+不建session")
  void login_wrongCredentials_401NoSession() throws Exception {
    when(userService.verify("admin", "wrong")).thenReturn(false);

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401))
        .andExpect(jsonPath("$.message").value("Invalid username or password"));
    verify(sessionService, never()).create(anyString());
  }

  @Test
  @DisplayName("login_缺字段_400")
  void login_missingFields_400() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"\",\"password\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("logout_HTTPS有cookie_清session+清Cookie且保留Secure")
  void logout_withCookie_clearsSession() throws Exception {
    mvc.perform(
            post("/api/v1/auth/logout")
                .secure(true)
                .cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-123")))
        .andExpect(status().isOk())
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")))
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Secure")));
    verify(sessionService).delete("sid-123");
  }

  @Test
  @DisplayName("logout_无cookie_幂等200仍清cookie")
  void logout_noCookie_idempotent() throws Exception {
    mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isOk());
    verify(sessionService, never()).delete(anyString());
  }

  @Test
  @DisplayName("me_有有效session_200返用户名")
  void me_validSession_200() throws Exception {
    when(sessionService.findValid("sid-123"))
        .thenReturn(Optional.of(newSession("admin", "sid-123")));

    mvc.perform(
            get("/api/v1/auth/me")
                .cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.username").value("admin"));
  }

  @Test
  @DisplayName("me_无cookie_401")
  void me_noCookie_401() throws Exception {
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  @DisplayName("me_session过期_401")
  void me_expiredSession_401() throws Exception {
    when(sessionService.findValid("sid-123")).thenReturn(Optional.empty());

    mvc.perform(
            get("/api/v1/auth/me")
                .cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-123")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  private static WebSession newSession(String username, String sessionId) {
    WebSession s = new WebSession();
    s.setSessionId(sessionId);
    s.setUsername(username);
    s.setExpiresAt(Instant.now().plusSeconds(3600));
    return s;
  }
}
