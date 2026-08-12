package io.oryxos.web.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebAuthProperties;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 012-web-auth 验收 harness：BasicAuthFilterTest——filter 校验口径钉死（Basic + session 两路径 + Accept 分流）。 用
 * standalone MockMvc + stub controller 映射 /admin/** 返 200（filter 放行后要有 handler，否则 404 误判）， mock
 * WebUserService/WebSessionService。 守：enabled=false 放行、Basic 对/错、session 有效放行/过期 401、 浏览器未登录跳
 * /admin/login、curl 未登录 401+WWW-Authenticate、/admin/login 放行。
 */
class BasicAuthFilterTest {

  private WebUserService userService;
  private WebSessionService sessionService;
  private WebAuthProperties properties;
  private MockMvc mvc;

  @Controller
  static class StubController {
    @GetMapping("/admin/")
    public void adminRoot() {}

    @GetMapping("/admin/login")
    public void adminLogin() {}
  }

  @BeforeEach
  void setUp() {
    userService = mock(WebUserService.class);
    sessionService = mock(WebSessionService.class);
    properties = new WebAuthProperties();
    BasicAuthFilter filter =
        new BasicAuthFilter(userService, sessionService, properties, new ObjectMapper());
    mvc = MockMvcBuilders.standaloneSetup(new StubController()).addFilter(filter).build();
  }

  @Test
  @DisplayName("enabled=false_无凭据放行_200")
  void disabled_passesThrough() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/admin/")).andExpect(status().isOk());
    verify(userService, never()).verify(anyString(), anyString());
  }

  @Test
  @DisplayName("enabled=true_无凭据_curl(Accept=application/json)_401+WWW-Authenticate")
  void enabledNoCredentials_curl_401WithChallenge() throws Exception {
    properties.setEnabled(true);
    mvc.perform(get("/admin/").accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Basic realm=\"OryxOS\""))
        .andExpect(jsonPath("$.code").value(401));
  }

  @Test
  @DisplayName("enabled=true_无凭据_浏览器(Accept=text/html)_302跳/admin/login")
  void enabledNoCredentials_browser_redirectsToLogin() throws Exception {
    properties.setEnabled(true);
    mvc.perform(get("/admin/").accept(MediaType.TEXT_HTML))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/admin/login"));
  }

  @Test
  @DisplayName("enabled=true_正确Basic凭据_200")
  void enabledCorrectBasicCredentials_200() throws Exception {
    properties.setEnabled(true);
    when(userService.verify("admin", "s3cret-pw")).thenReturn(true);

    mvc.perform(get("/admin/").header("Authorization", basic("admin", "s3cret-pw")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("enabled=true_错误Basic凭据_curl_401")
  void enabledWrongBasicCredentials_401() throws Exception {
    properties.setEnabled(true);
    when(userService.verify("admin", "wrong")).thenReturn(false);

    mvc.perform(
            get("/admin/")
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", basic("admin", "wrong")))
        .andExpect(status().isUnauthorized())
        .andExpect(header().string("WWW-Authenticate", "Basic realm=\"OryxOS\""));
  }

  @Test
  @DisplayName("enabled=true_有效session_cookie_200")
  void enabledValidSession_200() throws Exception {
    properties.setEnabled(true);
    WebSession session = newSession("admin");
    when(sessionService.findValid("sid-123")).thenReturn(Optional.of(session));

    mvc.perform(get("/admin/").cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-123")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("enabled=true_过期session_浏览器_302跳登录")
  void enabledExpiredSession_browser_redirectsToLogin() throws Exception {
    properties.setEnabled(true);
    when(sessionService.findValid("sid-123")).thenReturn(Optional.empty());

    mvc.perform(
            get("/admin/")
                .accept(MediaType.TEXT_HTML)
                .cookie(new jakarta.servlet.http.Cookie("oryxos_session", "sid-123")))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "/admin/login"));
  }

  @Test
  @DisplayName("enabled=true_禁用账号_Basic_401（verify返false）")
  void enabledDisabledAccount_401() throws Exception {
    properties.setEnabled(true);
    when(userService.verify("admin", "s3cret-pw")).thenReturn(false);

    mvc.perform(
            get("/admin/")
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", basic("admin", "s3cret-pw")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("/admin/login_放行（未登录也能访问）")
  void loginPath_passesThrough() throws Exception {
    properties.setEnabled(true);
    mvc.perform(get("/admin/login")).andExpect(status().isOk());
  }

  @Test
  @DisplayName("非Basic头_401")
  void nonBasicHeader_401() throws Exception {
    properties.setEnabled(true);
    mvc.perform(
            get("/admin/")
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer some-token"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("格式错的Basic头_401")
  void malformedBasic_401() throws Exception {
    properties.setEnabled(true);
    mvc.perform(
            get("/admin/")
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Basic !!!notbase64!!!"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("自定义realm_WWW-Authenticate头带自定义值")
  void customRealm_appearsInChallenge() throws Exception {
    properties.setEnabled(true);
    properties.setRealm("MySystem");
    mvc.perform(get("/admin/").accept(MediaType.APPLICATION_JSON))
        .andExpect(header().string("WWW-Authenticate", "Basic realm=\"MySystem\""));
  }

  private static String basic(String user, String pass) {
    return "Basic "
        + java.util.Base64.getEncoder()
            .encodeToString((user + ":" + pass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static WebSession newSession(String username) {
    WebSession s = new WebSession();
    s.setSessionId("sid-123");
    s.setUsername(username);
    s.setExpiresAt(Instant.now().plusSeconds(3600));
    return s;
  }
}
