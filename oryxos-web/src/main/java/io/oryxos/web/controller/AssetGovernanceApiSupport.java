package io.oryxos.web.controller;

import io.oryxos.core.auth.Principal;
import io.oryxos.core.policy.Action;
import io.oryxos.core.policy.AssetGovernance;
import io.oryxos.core.policy.AssetGovernanceStore;
import io.oryxos.core.policy.ResourceRef;
import io.oryxos.storage.AssetGovernanceEventRecorder;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.AssetGovernanceView;
import io.oryxos.web.security.AssetBindGuard;
import io.oryxos.web.security.PrincipalHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.util.function.Function;

/**
 * 三类资产共用的治理读写（041）：PUT 先 {@code decide(MANAGE_*)}，再写侧车并记变更事件。
 *
 * <p>放在 support 而不是复制三份 Controller 逻辑，是为了让「权限只有 decide 一条路径」可被单测盯住。
 */
final class AssetGovernanceApiSupport {

  private AssetGovernanceApiSupport() {}

  static ApiResponse<AssetGovernanceView> get(
      ResourceRef resource, Function<String, AssetGovernance> loader) {
    // GET 由 Filter 路径映射做 READ；这里不另开权限路径。PUT 才再 decide(MANAGE_*)。
    return ApiResponse.ok(AssetGovernanceView.from(loader.apply(resource.id())));
  }

  static ApiResponse<AssetGovernanceView> put(
      HttpServletRequest request,
      AssetBindGuard guard,
      AssetGovernanceStore store,
      AssetGovernanceEventRecorder recorder,
      Action action,
      ResourceRef resource,
      AssetGovernanceView body,
      Saver saver) {
    guard.requireManage(request, action, resource);
    AssetGovernance model = body == null ? AssetGovernance.empty() : body.toModel();
    saver.save(resource.id(), model);
    Principal actor = PrincipalHolder.get(request);
    if (recorder != null) {
      recorder.record(
          actor.describe(), resource.type(), resource.id(), AssetGovernanceStore.summarize(model));
    }
    return ApiResponse.ok(AssetGovernanceView.from(store.load(resource.type(), resource.id())));
  }

  @FunctionalInterface
  interface Saver {
    void save(String id, AssetGovernance governance);
  }
}
