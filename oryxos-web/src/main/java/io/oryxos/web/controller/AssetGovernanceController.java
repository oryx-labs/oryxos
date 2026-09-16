package io.oryxos.web.controller;

import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.storage.AssetGovernanceEventRecorder;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AssetGovernanceView;
import io.oryxos.web.security.AssetBindGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资产治理侧车读写（041）。路径落在既有 agents / skills / knowledge 前缀下，由 {@code RequestActionResolver} 的 MANAGE_*
 * 覆盖；PUT 再对具体资源 {@code decide}，以便 PRIVATE/OFFLINE 侧车生效。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification = "与既有 Agent/Skill API 同一内网假设；协作者为 Spring 注入共享单例，存同一引用正是意图。")
@RestController
public class AssetGovernanceController {

  private final AssetGovernanceStore store;

  private final AssetBindGuard guard;

  private final AssetGovernanceEventRecorder recorder;

  public AssetGovernanceController(
      AssetGovernanceStore store, AssetBindGuard guard, AssetGovernanceEventRecorder recorder) {
    this.store = store;
    this.guard = guard;
    this.recorder = recorder;
  }

  @GetMapping("/api/v1/agents/{name}/governance")
  public ApiResponse<AssetGovernanceView> getAgent(@PathVariable String name) {
    return AssetGovernanceApiSupport.get(ResourceRef.agent(name), store::loadAgent);
  }

  @PutMapping("/api/v1/agents/{name}/governance")
  public ApiResponse<AssetGovernanceView> putAgent(
      @PathVariable String name,
      @RequestBody AssetGovernanceView body,
      HttpServletRequest request) {
    return AssetGovernanceApiSupport.put(
        request,
        guard,
        store,
        recorder,
        Action.MANAGE_AGENTS,
        ResourceRef.agent(name),
        body,
        store::saveAgent);
  }

  @GetMapping("/api/v1/skills/{name}/governance")
  public ApiResponse<AssetGovernanceView> getSkill(@PathVariable String name) {
    return AssetGovernanceApiSupport.get(ResourceRef.skill(name), store::loadSkill);
  }

  @PutMapping("/api/v1/skills/{name}/governance")
  public ApiResponse<AssetGovernanceView> putSkill(
      @PathVariable String name,
      @RequestBody AssetGovernanceView body,
      HttpServletRequest request) {
    return AssetGovernanceApiSupport.put(
        request,
        guard,
        store,
        recorder,
        Action.MANAGE_SKILLS,
        ResourceRef.skill(name),
        body,
        store::saveSkill);
  }

  @GetMapping("/api/v1/knowledge/{name}/governance")
  public ApiResponse<AssetGovernanceView> getKnowledge(@PathVariable String name) {
    return AssetGovernanceApiSupport.get(ResourceRef.knowledge(name), store::loadKnowledge);
  }

  @PutMapping("/api/v1/knowledge/{name}/governance")
  public ApiResponse<AssetGovernanceView> putKnowledge(
      @PathVariable String name,
      @RequestBody AssetGovernanceView body,
      HttpServletRequest request) {
    return AssetGovernanceApiSupport.put(
        request,
        guard,
        store,
        recorder,
        Action.MANAGE_KNOWLEDGE,
        ResourceRef.knowledge(name),
        body,
        store::saveKnowledge);
  }
}
