package io.oryxos.web.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.auth.Role;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 041：绑定守卫只走 {@link AuthorizationService#decide}；PRIVATE 他属主拒绝由决策点返回。 */
class AssetBindGuardTest {

  private static final String SKILL = "secret-skill";

  @Test
  void bindDeniedWhenDecideDeniesPrivateOtherOwner() {
    AuthorizationService authorization = mock(AuthorizationService.class);
    when(authorization.decide(any(), eq(Action.MANAGE_SKILLS), eq(ResourceRef.skill(SKILL))))
        .thenReturn(AuthorizationService.Decision.denied("私有资产仅属主或管理员可访问"));
    AssetBindGuard guard = new AssetBindGuard(authorization);
    HttpServletRequest request = requestWith(Principal.user("bob", "bob", Set.of(Role.EDITOR)));

    assertThatThrownBy(() -> guard.requireSkillBind(request, SKILL))
        .isInstanceOf(AssetGovernanceAccessException.class)
        .hasMessageContaining("私有");
    verify(authorization).decide(any(), eq(Action.MANAGE_SKILLS), eq(ResourceRef.skill(SKILL)));
  }

  @Test
  void bindAllowedDoesNotThrow() {
    AuthorizationService authorization = mock(AuthorizationService.class);
    when(authorization.decide(any(), any(), any()))
        .thenReturn(AuthorizationService.Decision.ALLOWED);
    AssetBindGuard guard = new AssetBindGuard(authorization);
    HttpServletRequest request = requestWith(Principal.user("alice", "alice", Set.of(Role.EDITOR)));

    assertThatCode(() -> guard.requireSkillBind(request, SKILL)).doesNotThrowAnyException();

    ArgumentCaptor<ResourceRef> resource = ArgumentCaptor.forClass(ResourceRef.class);
    verify(authorization).decide(any(), eq(Action.MANAGE_SKILLS), resource.capture());
    assertThatCode(() -> {}).doesNotThrowAnyException();
    org.assertj.core.api.Assertions.assertThat(resource.getValue().id()).isEqualTo(SKILL);
  }

  private static HttpServletRequest requestWith(Principal principal) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getAttribute("io.oryxos.web.principal")).thenReturn(principal);
    return request;
  }
}
