package io.oryxos.web.security;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AuthorizationService;
import io.oryxos.core.policy.ResourceRef;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 绑定 / 调用的资产门禁薄封装（041）：只调既有 {@link AuthorizationService#decide}，不复制角色矩阵。
 *
 * <p>flag 关且容器注入 {@code ALLOW_ALL} 时，decide 恒允许，绑定路径与升级前一致。RBAC 已在 Filter 做过路径级裁决；这里用具体
 * ResourceRef（skill/knowledge/agent id）让装饰器能读到侧车（路径级 workspace 资源没有 id）。
 */
public class AssetBindGuard {

  private final AuthorizationService authorizationService;

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "authorizationService 为 Spring 注入共享单例，存同一引用正是意图。")
  public AssetBindGuard(AuthorizationService authorizationService) {
    this.authorizationService =
        authorizationService == null ? AuthorizationService.ALLOW_ALL : authorizationService;
  }

  /** 绑定 Skill 前：{@code decide(MANAGE_SKILLS, skill)}。 */
  public void requireSkillBind(HttpServletRequest request, String skillName) {
    require(request, Action.MANAGE_SKILLS, ResourceRef.skill(skillName));
  }

  /** 绑定知识库前：{@code decide(MANAGE_KNOWLEDGE, knowledge)}。 */
  public void requireKnowledgeBind(HttpServletRequest request, String kbName) {
    require(request, Action.MANAGE_KNOWLEDGE, ResourceRef.knowledge(kbName));
  }

  /** 调用 Agent 前：{@code decide(RUN_AGENT, agent)}，让 OFFLINE 侧车能挡下。 */
  public void requireAgentRun(HttpServletRequest request, String agentName) {
    require(request, Action.RUN_AGENT, ResourceRef.agent(agentName));
  }

  /** 写治理侧车前：同一动作词表上的 MANAGE_*。 */
  public void requireManage(HttpServletRequest request, Action action, ResourceRef resource) {
    require(request, action, resource);
  }

  private void require(HttpServletRequest request, Action action, ResourceRef resource) {
    Principal principal = PrincipalHolder.get(request);
    AuthorizationService.Decision decision =
        authorizationService.decide(principal, action, resource);
    if (!decision.allowed()) {
      throw new AssetGovernanceAccessException(decision.reason());
    }
  }
}
