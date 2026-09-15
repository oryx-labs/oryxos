package io.oryxos.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.auth.Role;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 040 验收 harness：OidcIdentityService——JIT 供给（C1-A）与条件权威角色刷新（C3-C，R14 行为表全行）钉死。 守：sub
 * 锚点稳定映射、首登派生唯一用户名（冲突后缀）、命中→覆写/未命中→保留本地、随机密码不可复用。
 */
class OidcIdentityServiceTest {

  private static final String ISSUER = "https://idp.example.com/realm";
  private static final Set<Role> DEFAULTS = Set.of(Role.VIEWER);

  private OidcIdentityRepository identityRepository;
  private WebUserRepository userRepository;
  private WebUserService userService;
  private OidcIdentityService service;

  @BeforeEach
  void setUp() {
    identityRepository = mock(OidcIdentityRepository.class);
    userRepository = mock(WebUserRepository.class);
    userService = mock(WebUserService.class);
    when(identityRepository.save(any(OidcIdentity.class))).thenAnswer(inv -> inv.getArgument(0));
    service = new OidcIdentityService(identityRepository, userRepository, userService);
  }

  @Test
  @DisplayName("R14首登_无命中_JIT供给默认角色VIEWER并建映射行")
  void firstLogin_noHits_provisionsWithDefaults() {
    when(identityRepository.findByIssuerAndSubject(ISSUER, "sub-1")).thenReturn(Optional.empty());
    when(userRepository.existsByUsername("alice")).thenReturn(false);

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-1", "alice@corp.com", "alice", Set.of(), DEFAULTS);

    assertThat(result.firstLogin()).isTrue();
    assertThat(result.username()).isEqualTo("alice");
    assertThat(result.roles()).containsExactly(Role.VIEWER);
    verify(userService).create(eq("alice"), anyString());
    verify(userService).setRoles("alice", DEFAULTS);
    ArgumentCaptor<OidcIdentity> captor = ArgumentCaptor.forClass(OidcIdentity.class);
    verify(identityRepository).save(captor.capture());
    assertThat(captor.getValue().getIssuer()).isEqualTo(ISSUER);
    assertThat(captor.getValue().getSubject()).isEqualTo("sub-1");
    assertThat(captor.getValue().getEmail()).isEqualTo("alice@corp.com");
  }

  @Test
  @DisplayName("R14首登_有命中_角色取命中集非默认档")
  void firstLogin_withHits_usesClaimRoles() {
    when(identityRepository.findByIssuerAndSubject(ISSUER, "sub-2")).thenReturn(Optional.empty());
    when(userRepository.existsByUsername("bob")).thenReturn(false);

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-2", null, "bob", Set.of(Role.ADMIN), DEFAULTS);

    assertThat(result.roles()).containsExactly(Role.ADMIN);
    verify(userService).setRoles("bob", Set.of(Role.ADMIN));
  }

  @Test
  @DisplayName("首登_用户名冲突_追加-2后缀")
  void firstLogin_usernameCollision_appendsSuffix() {
    when(identityRepository.findByIssuerAndSubject(ISSUER, "sub-3")).thenReturn(Optional.empty());
    when(userRepository.existsByUsername("alice")).thenReturn(true);
    when(userRepository.existsByUsername("alice-2")).thenReturn(false);

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-3", null, "alice", Set.of(), DEFAULTS);

    assertThat(result.username()).isEqualTo("alice-2");
  }

  @Test
  @DisplayName("首登_密码为随机弃置串_每次不同且非空")
  void firstLogin_randomDiscardedPassword() {
    when(identityRepository.findByIssuerAndSubject(any(), any())).thenReturn(Optional.empty());
    when(userRepository.existsByUsername(anyString())).thenReturn(false);

    service.login(ISSUER, "sub-a", null, "u1", Set.of(), DEFAULTS);
    service.login(ISSUER, "sub-b", null, "u2", Set.of(), DEFAULTS);

    ArgumentCaptor<String> pw = ArgumentCaptor.forClass(String.class);
    verify(userService).create(eq("u1"), pw.capture());
    verify(userService).create(eq("u2"), pw.capture());
    assertThat(pw.getAllValues().get(0)).hasSizeGreaterThanOrEqualTo(40);
    assertThat(pw.getAllValues().get(0)).isNotEqualTo(pw.getAllValues().get(1));
  }

  @Test
  @DisplayName("R14再登_有命中且与本地不同_覆写并标记rolesChanged（IdP权威撤组即降权）")
  void relogin_hitsDiffer_overwritesRoles() {
    stubExistingIdentity("sub-4", "carol");
    when(userService.rolesOf("carol")).thenReturn(Set.of(Role.ADMIN));

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-4", null, "ignored", Set.of(Role.VIEWER), DEFAULTS);

    assertThat(result.firstLogin()).isFalse();
    assertThat(result.rolesChanged()).isTrue();
    assertThat(result.roles()).containsExactly(Role.VIEWER);
    verify(userService).setRoles("carol", Set.of(Role.VIEWER));
  }

  @Test
  @DisplayName("R14再登_命中与本地相同_不写库不标记变化")
  void relogin_hitsEqual_noWrite() {
    stubExistingIdentity("sub-5", "dave");
    when(userService.rolesOf("dave")).thenReturn(Set.of(Role.EDITOR));

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-5", null, "ignored", Set.of(Role.EDITOR), DEFAULTS);

    assertThat(result.rolesChanged()).isFalse();
    verify(userService, never()).setRoles(anyString(), any());
  }

  @Test
  @DisplayName("R14再登_无命中_保留本地角色（本地权威，oryxos user role 有效）")
  void relogin_noHits_keepsLocalRoles() {
    stubExistingIdentity("sub-6", "erin");
    when(userService.rolesOf("erin")).thenReturn(Set.of(Role.ADMIN));

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-6", null, "ignored", Set.of(), DEFAULTS);

    assertThat(result.roles()).containsExactly(Role.ADMIN);
    assertThat(result.rolesChanged()).isFalse();
    verify(userService, never()).setRoles(anyString(), any());
  }

  @Test
  @DisplayName("再登_映射用户被删除_按首登规则重供给同名用户（锚点不变）")
  void relogin_userDeleted_reprovisions() {
    stubExistingIdentity("sub-7", "frank");
    when(userRepository.findByUsername("frank")).thenReturn(Optional.empty());

    OidcIdentityService.OidcLoginResult result =
        service.login(ISSUER, "sub-7", null, "ignored", Set.of(), DEFAULTS);

    assertThat(result.firstLogin()).isTrue();
    assertThat(result.username()).isEqualTo("frank");
    verify(userService).create(eq("frank"), anyString());
  }

  @Test
  @DisplayName("再登_更新last_login_at与email")
  void relogin_updatesLastLoginAndEmail() {
    OidcIdentity identity = stubExistingIdentity("sub-8", "gina");
    Instant before = identity.getLastLoginAt();
    when(userService.rolesOf("gina")).thenReturn(Set.of(Role.VIEWER));

    service.login(ISSUER, "sub-8", "gina@corp.com", "ignored", Set.of(), DEFAULTS);

    assertThat(identity.getLastLoginAt()).isAfterOrEqualTo(before);
    assertThat(identity.getEmail()).isEqualTo("gina@corp.com");
  }

  private OidcIdentity stubExistingIdentity(String subject, String username) {
    OidcIdentity identity = new OidcIdentity();
    identity.setIssuer(ISSUER);
    identity.setSubject(subject);
    identity.setUsername(username);
    identity.setFirstLoginAt(Instant.now().minusSeconds(3600));
    identity.setLastLoginAt(Instant.now().minusSeconds(3600));
    when(identityRepository.findByIssuerAndSubject(ISSUER, subject))
        .thenReturn(Optional.of(identity));
    WebUser user = new WebUser();
    user.setUsername(username);
    user.setEnabled(true);
    when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    return identity;
  }
}
