package io.oryxos.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import io.oryxos.core.auth.Role;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.OidcAuthRequestStore;
import io.oryxos.storage.OidcIdentityService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.security.oidc.IdTokenValidator;
import io.oryxos.web.security.oidc.OidcClient;
import io.oryxos.web.security.oidc.OidcErrorCode;
import io.oryxos.web.security.oidc.OidcFlowException;
import io.oryxos.web.security.oidc.OidcRoleResolver;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 040 验收 harness：OidcAuthController——发起/回调全路径钉死（standalone MockMvc + 全 mock，不碰网络与 DB）。 守：未启用
 * 404、错误分类矩阵（302 登录页 + login_failure 审计）、成功路径建 session/置 cookie/中转页/三类成功审计。
 */
class OidcAuthControllerTest {

  private static final String ISSUER = "https://idp.example.com/realm";

  private WebOidcProperties properties;
  private OidcClient oidcClient;
  private IdTokenValidator validator;
  private OidcAuthRequestStore store;
  private OidcIdentityService identityService;
  private WebSessionService sessionService;
  private AuthEventRecorder recorder;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    properties = new WebOidcProperties();
    properties.setEnabled(true);
    properties.setIssuer(ISSUER);
    properties.setClientId("oryxos-admin");
    oidcClient = mock(OidcClient.class);
    validator = mock(IdTokenValidator.class);
    store = mock(OidcAuthRequestStore.class);
    identityService = mock(OidcIdentityService.class);
    sessionService = mock(WebSessionService.class);
    recorder = mock(AuthEventRecorder.class);
    OidcRoleResolver roleResolver = new OidcRoleResolver(properties);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new OidcAuthController(
                    properties,
                    oidcClient,
                    validator,
                    roleResolver,
                    store,
                    identityService,
                    sessionService,
                    recorder))
            .build();
  }

  @Test
  @DisplayName("login_未启用_404")
  void login_disabled_404() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/auth/oidc/login")).andExpect(status().isNotFound());
    verify(store, never()).create();
  }

  @Test
  @DisplayName("login_启用_302到IdP授权端点")
  void login_enabled_redirectsToIdp() throws Exception {
    when(store.create())
        .thenReturn(new OidcAuthRequestStore.PendingAuth("st", "no", "verifier-123"));
    when(oidcClient.authorizeUrl(eq("st"), eq("no"), anyString()))
        .thenReturn("https://idp.example.com/authorize?x=1");

    mvc.perform(get("/api/v1/auth/oidc/login"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("https://idp.example.com/authorize?x=1"));
  }

  @Test
  @DisplayName("login_discovery不可达_302登录页error=idp_unreachable")
  void login_idpUnreachable_redirectsWithError() throws Exception {
    when(store.create()).thenReturn(new OidcAuthRequestStore.PendingAuth("st", "no", "v"));
    when(oidcClient.authorizeUrl(anyString(), anyString(), anyString()))
        .thenThrow(new OidcFlowException(OidcErrorCode.IDP_UNREACHABLE, "down"));

    mvc.perform(get("/api/v1/auth/oidc/login"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/admin/login?error=idp_unreachable"));
  }

  @Test
  @DisplayName("callback_未启用_404")
  void callback_disabled_404() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/auth/oidc/callback").param("state", "s"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("callback_IdP带error参数_idp_error且落login_failure")
  void callback_idpError() throws Exception {
    mvc.perform(
            get("/api/v1/auth/oidc/callback").param("error", "access_denied").param("state", "s"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/admin/login?error=idp_error"));
    assertFailureRecorded("idp_error");
  }

  @Test
  @DisplayName("callback_state未知或重放_invalid_state")
  void callback_invalidState() throws Exception {
    when(store.consume("replayed")).thenReturn(Optional.empty());
    mvc.perform(get("/api/v1/auth/oidc/callback").param("code", "c").param("state", "replayed"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/admin/login?error=invalid_state"));
    assertFailureRecorded("invalid_state");
  }

  @Test
  @DisplayName("callback_令牌校验失败_按分类302且不建session")
  void callback_validatorFailure_categorized() throws Exception {
    when(store.consume("st")).thenReturn(new1());
    when(oidcClient.exchangeCodeForIdToken("c", "verifier")).thenReturn("jwt");
    when(validator.validate("jwt", "nonce"))
        .thenThrow(new OidcFlowException(OidcErrorCode.EXPIRED_TOKEN, "expired"));

    mvc.perform(get("/api/v1/auth/oidc/callback").param("code", "c").param("state", "st"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/admin/login?error=expired_token"));
    verify(sessionService, never()).create(anyString());
    assertFailureRecorded("expired_token");
  }

  @Test
  @DisplayName("callback_供给异常_provisioning_failed")
  void callback_provisioningFailure() throws Exception {
    when(store.consume("st")).thenReturn(new1());
    when(oidcClient.exchangeCodeForIdToken("c", "verifier")).thenReturn("jwt");
    when(validator.validate("jwt", "nonce")).thenReturn(claims());
    when(identityService.login(any(), any(), any(), any(), any(), any()))
        .thenThrow(new RuntimeException("db down"));

    mvc.perform(get("/api/v1/auth/oidc/callback").param("code", "c").param("state", "st"))
        .andExpect(status().isFound())
        .andExpect(redirectedUrl("/admin/login?error=provisioning_failed"));
  }

  @Test
  @DisplayName("callback_成功_建session置cookie+中转页+login_success与mapping_created审计")
  void callback_success_fullPath() throws Exception {
    when(store.consume("st")).thenReturn(new1());
    when(oidcClient.exchangeCodeForIdToken("c", "verifier")).thenReturn("jwt");
    when(validator.validate("jwt", "nonce")).thenReturn(claims());
    when(identityService.login(
            eq(ISSUER), eq("sub-1"), eq("alice@corp.com"), eq("alice"), any(), any()))
        .thenReturn(
            new OidcIdentityService.OidcLoginResult("alice", Set.of(Role.VIEWER), true, false));
    WebSession session = new WebSession();
    session.setSessionId("sid-oidc");
    session.setUsername("alice");
    session.setExpiresAt(Instant.now().plusSeconds(3600));
    when(sessionService.create("alice")).thenReturn(session);

    mvc.perform(get("/api/v1/auth/oidc/callback").param("code", "c").param("state", "st"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Set-Cookie", org.hamcrest.Matchers.containsString("oryxos_session=sid-oidc")))
        .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/")));

    ArgumentCaptor<AuthEventRecorder.AuthEventData> events =
        ArgumentCaptor.forClass(AuthEventRecorder.AuthEventData.class);
    verify(recorder, org.mockito.Mockito.times(2)).record(events.capture());
    assertThat(events.getAllValues().get(0).eventType()).isEqualTo("login_success");
    assertThat(events.getAllValues().get(0).externalSubject()).isEqualTo("sub-1");
    assertThat(events.getAllValues().get(1).eventType()).isEqualTo("mapping_created");
  }

  private Optional<OidcAuthRequestStore.PendingAuth> new1() {
    return Optional.of(new OidcAuthRequestStore.PendingAuth("st", "nonce", "verifier"));
  }

  private static JWTClaimsSet claims() {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject("sub-1")
        .claim("email", "alice@corp.com")
        .claim("preferred_username", "Alice")
        .build();
  }

  private void assertFailureRecorded(String reason) {
    ArgumentCaptor<AuthEventRecorder.AuthEventData> captor =
        ArgumentCaptor.forClass(AuthEventRecorder.AuthEventData.class);
    verify(recorder).record(captor.capture());
    assertThat(captor.getValue().eventType()).isEqualTo("login_failure");
    assertThat(captor.getValue().failureReason()).isEqualTo(reason);
  }
}
