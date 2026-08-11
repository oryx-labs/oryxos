package io.oryxos.web.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import io.oryxos.web.config.WebAuthProperties;
import io.oryxos.web.security.LoginAttemptService;
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
  private WebAuthProperties properties;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    userService = mock(WebUserService.class);
    sessionService = mock(WebSessionService.class);
    properties = new WebAuthProperties();
    properties.setEnabled(true);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AuthApiController(
                    userService, sessionService, properties, new LoginAttemptService()))
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
        .andExpect(jsonPath("$.data.authenticationEnabled").value(true))
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
  @DisplayName("login_连续5次失败_第6次429且不再碰密码校验")
  void login_fiveFailures_sixthBlocked429() throws Exception {
    when(userService.verify("admin", "wrong")).thenReturn(false);

    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
          .andExpect(status().isUnauthorized());
    }

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value(429))
        .andExpect(jsonPath("$.message").value("Too many failed login attempts, try again later"));
    // 锁定期内不碰 verify：第 6 次请求不能成为密码探针。
    verify(userService, times(5)).verify("admin", "wrong");
    verify(sessionService, never()).create(anyString());
  }

  @Test
  @DisplayName("login_锁定仅限同用户名+同IP_其他用户名不受影响")
  void login_lockScopedToUsernameIpPair() throws Exception {
    when(userService.verify(anyString(), anyString())).thenReturn(false);

    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
          .andExpect(status().isUnauthorized());
    }

    // admin 已锁，但另一用户名从同 IP 登录仍走正常校验（401 而非 429）。
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"other\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("login_失败后成功登录_计数清零后续失败重新计")
  void login_successResetsFailureCount() throws Exception {
    when(userService.verify("admin", "wrong")).thenReturn(false);
    when(userService.verify("admin", "s3cret-pw")).thenReturn(true);
    when(sessionService.create("admin")).thenReturn(newSession("admin", "sid-123"));

    for (int i = 0; i < 4; i++) {
      mvc.perform(
              post("/api/v1/auth/login")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
          .andExpect(status().isUnauthorized());
    }

    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"s3cret-pw\"}"))
        .andExpect(status().isOk());

    // 清零后再失败一次：仍是 401（重新从 1 计），而非累计到第 5 次触发 429。
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized());
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
  @DisplayName("me_认证关闭_200返开关状态且不要求session")
  void me_authDisabled_200() throws Exception {
    properties.setEnabled(false);

    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.authenticationEnabled").value(false))
        .andExpect(jsonPath("$.data.username").doesNotExist());
    verify(sessionService, never()).findValid(anyString());
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
        .andExpect(jsonPath("$.data.authenticationEnabled").value(true))
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
