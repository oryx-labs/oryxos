package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.provider.ProviderDef;
import io.oryxos.core.provider.ProviderRegistry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreateProviderRequest;
import io.oryxos.web.controller.dto.ProviderView;
import io.oryxos.web.controller.dto.UpdateProviderRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider 动态注册表 CRUD（第 31 节）：LLM 接入点运行时增删改，管理台管、运行时按名动态建 ChatModel。
 *
 * <p>薄转发给 {@link ProviderRegistry}。错误码沿用既有：name 冲突 / 定义非法 → 400；不存在 → 404；统一 {@code ApiResponse}
 * 信封。名为 {@code mock} 的 provider 免 base-url（走内置假模型），其余必须有 base-url。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway). registry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/providers")
public class ProviderApiController {

  private static final String MOCK = "mock";

  private final ProviderRegistry registry;

  public ProviderApiController(ProviderRegistry registry) {
    this.registry = registry;
  }

  @PostMapping
  public ApiResponse<ProviderView> create(@RequestBody CreateProviderRequest req) {
    String name = req == null ? null : req.name();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("provider 名为空"); // → 400
    }
    if (registry.exists(name)) {
      throw new IllegalArgumentException("provider 已存在: " + name); // → 400
    }
    validate(name, req.baseUrl());
    ProviderDef saved =
        registry.save(new ProviderDef(name, req.apiKey(), req.baseUrl(), req.description()));
    return ApiResponse.ok(ProviderView.from(saved));
  }

  @GetMapping
  public ApiResponse<List<ProviderView>> list() {
    return ApiResponse.ok(registry.list().stream().map(ProviderView::from).toList());
  }

  @GetMapping("/{name}")
  public ApiResponse<ProviderView> get(@PathVariable String name) {
    return ApiResponse.ok(
        registry
            .find(name)
            .map(ProviderView::from)
            .orElseThrow(() -> new ResourceNotFoundException("provider 不存在: " + name)));
  }

  @PutMapping("/{name}")
  public ApiResponse<ProviderView> update(
      @PathVariable String name, @RequestBody UpdateProviderRequest req) {
    if (!registry.exists(name)) {
      throw new ResourceNotFoundException("provider 不存在: " + name); // → 404
    }
    validate(name, req.baseUrl());
    ProviderDef saved =
        registry.save(new ProviderDef(name, req.apiKey(), req.baseUrl(), req.description()));
    return ApiResponse.ok(ProviderView.from(saved));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    if (!registry.exists(name)) {
      throw new ResourceNotFoundException("provider 不存在: " + name); // → 404
    }
    registry.delete(name);
    return ApiResponse.ok(null);
  }

  /** 非 mock 的 provider 必须有 base-url（否则运行时建不出 OpenAI 兼容 ChatModel）。 */
  private static void validate(String name, String baseUrl) {
    if (MOCK.equals(name)) {
      return;
    }
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("provider " + name + " 缺少 base-url");
    }
  }
}
