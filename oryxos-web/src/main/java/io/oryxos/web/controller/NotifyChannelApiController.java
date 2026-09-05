package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.notify.NotifyChannelDef;
import io.oryxos.core.notify.NotifyChannelRegistry;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreateNotifyChannelRequest;
import io.oryxos.web.controller.dto.CredentialMasks;
import io.oryxos.web.controller.dto.NotifyChannelView;
import io.oryxos.web.controller.dto.UpdateNotifyChannelRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知渠道注册表 CRUD（第 31 节）：全局命名的 notify 出口，管理台增删改查、Agent 按名引用。
 *
 * <p>薄转发给 {@link NotifyChannelRegistry}。错误码沿用既有口径：名字冲突 / 定义非法 → 400 （{@code
 * IllegalArgumentException}）；不存在 → 404（{@code ResourceNotFoundException}）；统一 {@code ApiResponse}
 * 信封。type 必须是已装配的渠道实现之一（webhook/feishu/wecom/dingtalk/email）。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway). registry 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/notify-channels")
public class NotifyChannelApiController {

  private static final String TYPE_EMAIL = "email";

  /** email 渠道 port 的合法上限（TCP 端口最大值）。 */
  private static final int MAX_PORT = 65535;

  private static final Set<String> SUPPORTED_TYPES =
      Set.of("webhook", "feishu", "wecom", "dingtalk", TYPE_EMAIL);

  private final NotifyChannelRegistry registry;

  public NotifyChannelApiController(NotifyChannelRegistry registry) {
    this.registry = registry;
  }

  @PostMapping
  public ApiResponse<NotifyChannelView> create(@RequestBody CreateNotifyChannelRequest req) {
    String name = req == null ? null : req.name();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("渠道名为空"); // → 400
    }
    if (registry.exists(name)) {
      throw new IllegalArgumentException("通知渠道已存在: " + name); // → 400
    }
    Map<String, String> config = req.config();
    String url = normalizeUrl(req.type(), req.url());
    validate(req.type(), url, config);
    NotifyChannelDef saved =
        registry.save(new NotifyChannelDef(name, req.type(), url, req.description(), config));
    return ApiResponse.ok(NotifyChannelView.from(saved));
  }

  @GetMapping
  public ApiResponse<List<NotifyChannelView>> list() {
    return ApiResponse.ok(registry.list().stream().map(NotifyChannelView::from).toList());
  }

  @GetMapping("/{name}")
  public ApiResponse<NotifyChannelView> get(@PathVariable String name) {
    return ApiResponse.ok(
        registry
            .find(name)
            .map(NotifyChannelView::from)
            .orElseThrow(() -> new ResourceNotFoundException("通知渠道不存在: " + name)));
  }

  @PutMapping("/{name}")
  public ApiResponse<NotifyChannelView> update(
      @PathVariable String name, @RequestBody UpdateNotifyChannelRequest req) {
    NotifyChannelDef existing =
        registry
            .find(name)
            .orElseThrow(() -> new ResourceNotFoundException("通知渠道不存在: " + name)); // → 404
    // 022：前端编辑表单回填的是敏感项掩码；提交掩码原样或留空 = 未修改，保留原值——否则打码值会覆盖真实凭证
    Map<String, String> config = keepUnchangedSecrets(existing.config(), req.config());
    String url = normalizeUrl(req.type(), req.url());
    // webhook URL 同口径：提交值等于原 URL 的掩码 = 未修改，保留原 URL
    if (CredentialMasks.maskWebhookUrl(existing.url()).equals(url)) {
      url = existing.url();
    }
    validate(req.type(), url, config);
    NotifyChannelDef saved =
        registry.save(new NotifyChannelDef(name, req.type(), url, req.description(), config));
    return ApiResponse.ok(NotifyChannelView.from(saved));
  }

  /** 敏感项未修改判定（ProviderApiController 同款范式）：提交值为空或等于原值掩码 → 沿用原值；否则取新值。 */
  private static Map<String, String> keepUnchangedSecrets(
      Map<String, String> existing, Map<String, String> submitted) {
    if (submitted == null || submitted.isEmpty()) {
      return submitted;
    }
    Map<String, String> masked = NotifyChannelView.maskConfig(existing);
    Map<String, String> out = new java.util.LinkedHashMap<>();
    submitted.forEach(
        (key, value) -> {
          boolean unchanged =
              io.oryxos.core.secret.SensitiveConfigKeys.isSensitive(key)
                  && existing.containsKey(key)
                  && (value == null || value.isBlank() || value.equals(masked.get(key)));
          out.put(key, unchanged ? existing.get(key) : value);
        });
    return out;
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    if (!registry.exists(name)) {
      throw new ResourceNotFoundException("通知渠道不存在: " + name); // → 404
    }
    registry.delete(name);
    return ApiResponse.ok(null);
  }

  private static void validate(String type, String url, Map<String, String> config) {
    if (type == null || !SUPPORTED_TYPES.contains(type)) {
      throw new IllegalArgumentException("不支持的渠道类型: " + type + "（支持: " + SUPPORTED_TYPES + "）");
    }
    if (TYPE_EMAIL.equals(type)) {
      validateEmail(config);
    } else if (url == null || url.isBlank()) {
      throw new IllegalArgumentException("渠道 url 为空");
    }
  }

  private static void validateEmail(Map<String, String> config) {
    if (config == null) {
      throw new IllegalArgumentException("email 渠道缺少配置（需 host/port/from/to）");
    }
    requireConfig(config, "host");
    requireConfig(config, "from");
    requireConfig(config, "to");
    String portRaw = config.get("port");
    if (portRaw == null || portRaw.isBlank()) {
      throw new IllegalArgumentException("email 渠道缺少配置键 port");
    }
    int port;
    try {
      port = Integer.parseInt(portRaw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("email 渠道 port 不是整数: " + portRaw);
    }
    if (port < 1 || port > MAX_PORT) {
      throw new IllegalArgumentException("email 渠道 port 非法（须 1~" + MAX_PORT + "）: " + portRaw);
    }
  }

  private static void requireConfig(Map<String, String> config, String key) {
    String value = config.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("email 渠道缺少配置键 " + key);
    }
  }

  private static String normalizeUrl(String type, String url) {
    return TYPE_EMAIL.equals(type) ? "" : url;
  }
}
