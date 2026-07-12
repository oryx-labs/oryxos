package io.oryxos.web.controller;

import io.oryxos.core.sandbox.SandboxWhitelist;
import io.oryxos.core.sandbox.SandboxWhitelist.Category;
import io.oryxos.web.common.ApiResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员运行时操作 Sandbox 白名单的三个端点：查询 / 增加 / 删除。三类白名单（文件 / 命令 / 域名）由 {@code category} 路径变量区分。改动即刻生效于
 * {@code WhitelistSandbox}（下一次工具调用就按新白名单校验）。
 *
 * <p>依赖 core 的 {@link SandboxWhitelist} 契约（依赖倒置）——oryxos-web 不认 oryxos-tool 的实现类。
 *
 * <p>安全边界：核心阶段 Web API 无认证（假设内网）。这组端点等于远程调整安全护栏，务必靠网络层隔离 / 反代鉴权兜底； 非法类别或空值返回 400（{@code
 * GlobalExceptionHandler} 统一翻译 {@link IllegalArgumentException}）。
 */
@RestController
@RequestMapping("/api/v1/sandbox/whitelist")
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP2", "SPRING_ENDPOINT"},
    justification =
        "EI_EXPOSE_REP2：whitelist 是 Spring 注入的单例服务，共享引用正是意图（无法也不应防御性拷贝）。"
            + "SPRING_ENDPOINT：核心阶段 Web API 无认证是设计前提（假设内网 + 网络层兜底），"
            + "已在类 Javadoc 与 core 契约注明；端点鉴权属扩展阶段。")
public class SandboxWhitelistController {

  private final SandboxWhitelist whitelist;

  public SandboxWhitelistController(SandboxWhitelist whitelist) {
    this.whitelist = whitelist;
  }

  /** 查询：一次返回三类白名单当前全部条目。 */
  @GetMapping
  public ApiResponse<Map<String, List<String>>> list() {
    Map<String, List<String>> all = new LinkedHashMap<>();
    for (Category category : Category.values()) {
      all.put(category.name().toLowerCase(Locale.ROOT), whitelist.list(category));
    }
    return ApiResponse.ok(all);
  }

  /** 增加：向某类白名单加一条（幂等），返回是否新增及该类最新全量。 */
  @PostMapping("/{category}")
  public ApiResponse<WhitelistChange> add(
      @PathVariable String category, @RequestBody(required = false) WhitelistEntryRequest body) {
    Category parsed = parseCategory(category);
    String value = requireValue(body);
    boolean changed = whitelist.add(parsed, value);
    return ApiResponse.ok(
        new WhitelistChange(
            parsed.name().toLowerCase(Locale.ROOT), value, changed, whitelist.list(parsed)));
  }

  /** 删除：从某类白名单删一条，返回是否命中及该类最新全量。 */
  @DeleteMapping("/{category}")
  public ApiResponse<WhitelistChange> remove(
      @PathVariable String category, @RequestParam String value) {
    Category parsed = parseCategory(category);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("删除白名单需提供 value 查询参数");
    }
    boolean changed = whitelist.remove(parsed, value);
    return ApiResponse.ok(
        new WhitelistChange(
            parsed.name().toLowerCase(Locale.ROOT), value, changed, whitelist.list(parsed)));
  }

  private static Category parseCategory(String category) {
    try {
      return Category.valueOf(category.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("未知的白名单类别: " + category + "（可选 file / shell / http）");
    }
  }

  private static String requireValue(WhitelistEntryRequest body) {
    if (body == null || body.value() == null || body.value().isBlank()) {
      throw new IllegalArgumentException("请求体缺少 value（要加入白名单的路径 / 命令 / 域名）");
    }
    return body.value();
  }

  /** 增加请求体：{@code {"value": "*.example.com"}}。 */
  public record WhitelistEntryRequest(String value) {}

  /** 增删结果：改动的类别、条目、是否实际变更、该类最新全量。 */
  public record WhitelistChange(
      String category, String value, boolean changed, List<String> entries) {

    public WhitelistChange {
      entries = entries == null ? List.of() : List.copyOf(entries);
    }
  }
}
