package io.oryxos.web.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.storage.AuthEventRecorder;
import io.oryxos.storage.AuthEventType;
import io.oryxos.storage.IdentityMapping;
import io.oryxos.storage.IdentityMappingService;
import io.oryxos.storage.WebSession;
import io.oryxos.storage.WebSessionService;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.GlobalExceptionHandler;
import io.oryxos.web.config.WebOidcProperties;
import io.oryxos.web.oidc.OidcAuthService.OidcLoginResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 040 OIDC first cut：flag / PKCE state / 映射 / session / 禁止 AuthorizationService 交互。 */
class OidcAuthServiceTest {

  private WebOidcProperties properties;
  private OidcTokenClient tokenClient;
  private OidcPendingStore pendingStore;
  private IdentityMappingService mappingService;
  private WebUserService userService;
  private WebSessionService sessionService;
  private AuthEventRecorder authEventRecorder;
  private AuthorizationService authorizationService;
  private OidcAuthService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    properties = new WebOidcProperties();
    properties.setEnabled(true);
    properties.setIssuer("https://idp.example");
    properties.setClientId("oryxos");
    properties.setRedirectUri("https://app.example/api/v1/auth/oidc/callback");
    properties.setAuthorizationEndpoint("https://idp.example/authorize");
    tokenClient = mock(OidcTokenClient.class);
    pendingStore = new OidcPendingStore(java.time.Duration.ofMinutes(10));
    mappingService = mock(IdentityMappingService.class);
    userService = mock(WebUserService.class);
    sessionService = mock(WebSessionService.class);
    authEventRecorder = mock(AuthEventRecorder.class);
    authorizationService = mock(AuthorizationService.class);
    service =
        new OidcAuthService(
            properties,
            tokenClient,
            pendingStore,
            mappingService,
            userService,
            sessionService,
            authEventRecorder);
    mvc =
        MockMvcBuilders.standaloneSetup(new OidcAuthController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    when(tokenClient.resolveAuthorizationEndpoint(any()))
        .thenReturn("https://idp.example/authorize");
  }

  @Test
  @DisplayName("flag关闭_login返回404")
  void login_disabled_returns404() throws Exception {
    properties.setEnabled(false);
    mvc.perform(get("/api/v1/auth/oidc/login")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("state不匹配_失败且不建session")
  void callback_stateMismatch_fails() {
    OidcLoginResult result = service.completeLogin("code", "unknown-state");
    assertThat(result.isSuccess()).isFalse();
    verify(sessionService, never()).create(anyString());
    verify(authEventRecorder)
        .recordBestEffort(eq(AuthEventType.LOGIN_FAILURE), any(), eq("invalid_or_expired_state"));
  }

  @Test
  @DisplayName("未映射subject_无session+LOGIN_FAILURE")
  void callback_unmapped_noSession() {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(eq("code"), eq("verifier"), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", null));
    when(mappingService.findByIssuerAndSubject("https://idp.example", "sub-1"))
        .thenReturn(Optional.empty());

    OidcLoginResult result = service.completeLogin("code", "st");
    assertThat(result.isSuccess()).isFalse();
    verify(sessionService, never()).create(anyString());
    verify(authEventRecorder)
        .recordBestEffort(eq(AuthEventType.LOGIN_FAILURE), eq("sub-1"), eq("unmapped_subject"));
  }

  @Test
  @DisplayName("已映射启用用户_建session+LOGIN_SUCCESS")
  void callback_mappedEnabled_createsSession() throws Exception {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(eq("code"), eq("verifier"), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", "a@b.c"));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setIssuer("https://idp.example");
    mapping.setSubject("sub-1");
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject("https://idp.example", "sub-1"))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid-oidc");
    session.setUsername("alice");
    session.setExpiresAt(Instant.now().plusSeconds(3600));
    when(sessionService.create("alice")).thenReturn(session);

    mvc.perform(
            get("/api/v1/auth/oidc/callback")
                .param("code", "code")
                .param("state", "st")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("alice"))
        .andExpect(
            header()
                .string(
                    "Set-Cookie", org.hamcrest.Matchers.containsString("oryxos_session=sid-oidc")));

    verify(sessionService).create("alice");
    verify(authEventRecorder)
        .recordOrThrow(eq(AuthEventType.LOGIN_SUCCESS), eq("alice"), anyString());
    verify(userService, never()).setRoles(anyString(), any());
    verifyNoInteractions(authorizationService);
  }

  @Test
  @DisplayName("callback路径_零交互AuthorizationService")
  void callback_neverCallsAuthorizationService() {
    pendingStore.put("st", "verifier");
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(new OidcIdTokenClaims("https://idp.example", "sub-1", null));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verifyNoInteractions(authorizationService);
    verify(authorizationService, never()).decide(any(), any(), any());
    verify(userService, never()).setRoles(anyString(), any());
  }

  @Test
  @DisplayName("命中组映射时写角色且仍不调用AuthorizationService")
  void callback_matchingGroup_writesRolesWithoutDecide() {
    pendingStore.put("st", "verifier");
    properties.setGroupRoles(Map.of("oryxos-editors", "EDITOR"));
    when(tokenClient.exchangeAndValidate(anyString(), anyString(), any()))
        .thenReturn(
            new OidcIdTokenClaims("https://idp.example", "sub-1", null, List.of("oryxos-editors")));
    IdentityMapping mapping = new IdentityMapping();
    mapping.setUsername("alice");
    when(mappingService.findByIssuerAndSubject(anyString(), anyString()))
        .thenReturn(Optional.of(mapping));
    when(userService.isEnabledUser("alice")).thenReturn(true);
    WebSession session = new WebSession();
    session.setSessionId("sid");
    session.setUsername("alice");
    when(sessionService.create("alice")).thenReturn(session);

    assertThat(service.completeLogin("code", "st").isSuccess()).isTrue();
    verify(userService).setRoles("alice", Set.of(Role.EDITOR));
    verify(authEventRecorder)
        .recordOrThrow(eq(AuthEventType.GROUP_ROLE_SYNC), eq("alice"), eq("roles=[EDITOR]"));
    verifyNoInteractions(authorizationService);
  }
}
