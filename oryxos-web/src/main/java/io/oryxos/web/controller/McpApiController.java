package io.oryxos.web.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.oryxos.core.mcp.McpCatalogEntry;
import io.oryxos.core.mcp.McpServerAdmin;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.EnableMcpCatalogRequest;
import io.oryxos.web.controller.dto.McpCatalogView;
import io.oryxos.web.controller.dto.McpServerStatusView;
import io.oryxos.web.controller.dto.McpServerView;
import io.oryxos.web.error.ResourceNotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP server 管理台 CRUD（31 节）：薄转发给 {@link McpServerAdmin}（依赖倒置——oryxos-web 只认这个 core 契约， 具体实现在
 * oryxos-tool，同 {@code SandboxWhitelistController} 之于 {@code SandboxWhitelist} 的既有分层方式）。
 *
 * <p>增/改/删都是"落盘 + 立即生效"：加一个就立刻尝试连接，删一个就立刻断开——不需要重启 OryxOS。name 冲突 / 定义非法 → 400； 不存在 → 404；统一 {@code
 * ApiResponse} 信封。
 */
@SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. admin 是 Spring 注入的共享单例，构造注入共享同一引用正是意图。")
@RestController
@RequestMapping("/api/v1/mcp-servers")
public class McpApiController {

  private final McpServerAdmin admin;

  public McpApiController(McpServerAdmin admin) {
    this.admin = admin;
  }

  @GetMapping
  public ApiResponse<List<McpServerView>> list() {
    return ApiResponse.ok(admin.list().stream().map(McpServerView::from).toList());
  }

  @GetMapping("/status")
  public ApiResponse<List<McpServerStatusView>> status() {
    return ApiResponse.ok(admin.status().stream().map(McpServerStatusView::from).toList());
  }

  @GetMapping("/catalog")
  public ApiResponse<List<McpCatalogView>> catalog() {
    return ApiResponse.ok(admin.catalog().stream().map(McpCatalogView::from).toList());
  }

  @PostMapping
  public ApiResponse<McpServerView> add(@RequestBody McpServerView req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
      throw new IllegalArgumentException("MCP server 名为空"); // → 400
    }
    return ApiResponse.ok(McpServerView.from(admin.add(req.toConfig())));
  }

  /** 从内置目录一键启用：套模板 + 用户填的凭证，构造出一份 config 直接 add。 */
  @PostMapping("/catalog/{catalogId}/enable")
  public ApiResponse<McpServerView> enableFromCatalog(
      @PathVariable String catalogId, @RequestBody EnableMcpCatalogRequest req) {
    McpCatalogEntry entry =
        admin.catalog().stream()
            .filter(e -> e.id().equals(catalogId))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("内置目录不存在: " + catalogId));
    String name =
        req == null || req.name() == null || req.name().isBlank() ? entry.id() : req.name();
    Map<String, String> credentials = req == null ? Map.of() : req.credentials();
    McpServerConfig config = fromCatalog(entry, name, credentials);
    return ApiResponse.ok(McpServerView.from(admin.add(config)));
  }

  @PutMapping("/{name}")
  public ApiResponse<McpServerView> update(
      @PathVariable String name, @RequestBody McpServerView req) {
    if (admin.list().stream().noneMatch(c -> c.name().equals(name))) {
      throw new ResourceNotFoundException("MCP server 不存在: " + name); // → 404
    }
    McpServerConfig config =
        new McpServerConfig(
            name, req.transport(), req.command(), req.env(), req.url(), req.headers());
    return ApiResponse.ok(McpServerView.from(admin.update(name, config)));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    admin.remove(name); // 幂等：不存在也不报错，同 AgentLifecycleService.delete 口径
    return ApiResponse.ok(null);
  }

  /** 目录模板 + 用户凭证 -> 一份可直接 add 的 config。http 传输目前只有 github 这一条，直接拼 Bearer header。 */
  private static McpServerConfig fromCatalog(
      McpCatalogEntry entry, String name, Map<String, String> credentials) {
    if (McpServerConfig.TRANSPORT_HTTP.equals(entry.transport())) {
      Map<String, String> headers = new LinkedHashMap<>();
      if (!entry.requiredEnv().isEmpty()) {
        String token = credentials.get(entry.requiredEnv().get(0));
        if (token != null && !token.isBlank()) {
          headers.put("Authorization", "Bearer " + token);
        }
      }
      return new McpServerConfig(
          name, entry.transport(), null, Map.of(), entry.urlTemplate(), headers);
    }
    Map<String, String> env = new LinkedHashMap<>();
    for (String key : entry.requiredEnv()) {
      String value = credentials.get(key);
      if (value != null && !value.isBlank()) {
        env.put(key, value);
      }
    }
    return new McpServerConfig(
        name, entry.transport(), entry.commandTemplate(), env, null, Map.of());
  }
}
