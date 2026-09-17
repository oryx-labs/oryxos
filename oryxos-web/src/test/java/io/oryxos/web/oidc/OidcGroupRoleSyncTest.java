package io.oryxos.web.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.oryxos.core.auth.Role;
import io.oryxos.storage.WebUserService;
import io.oryxos.web.config.WebOidcProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OidcGroupRoleSyncTest {

  @Test
  void emptyMapDoesNotWriteRoles() {
    WebUserService users = mock(WebUserService.class);
    OidcGroupRoleSync sync = new OidcGroupRoleSync(users);

    assertThat(sync.apply("alice", List.of("oryxos-editors"), new WebOidcProperties())).isEmpty();
    verify(users, never())
        .setRoles(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void matchingGroupsReplaceRolesWithoutTouchingUnmatchedLogin() {
    WebUserService users = mock(WebUserService.class);
    WebOidcProperties properties = new WebOidcProperties();
    properties.setGroupRoles(Map.of("oryxos-editors", "editor", "oryxos-admins", "ADMIN"));
    OidcGroupRoleSync sync = new OidcGroupRoleSync(users);

    assertThat(sync.apply("alice", List.of("oryxos-editors", "other"), properties))
        .contains(Set.of(Role.EDITOR));
    verify(users).setRoles("alice", Set.of(Role.EDITOR));

    assertThat(sync.apply("alice", List.of("unknown"), properties)).isEmpty();
    verify(users, never()).setRoles("alice", Set.of());
  }

  @Test
  void claimShapesNormalize() {
    assertThat(OidcGroupClaims.normalize(null)).isEmpty();
    assertThat(OidcGroupClaims.normalize("  ops  ")).containsExactly("ops");
    assertThat(OidcGroupClaims.normalize("a, b")).containsExactly("a", "b");
    assertThat(OidcGroupClaims.normalize(List.of("g1", " ", "g2"))).containsExactly("g1", "g2");
  }
}
