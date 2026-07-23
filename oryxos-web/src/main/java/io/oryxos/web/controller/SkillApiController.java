package io.oryxos.web.controller;

import io.oryxos.core.skill.SkillService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreateSkillRequest;
import io.oryxos.web.controller.dto.ImportSkillRequest;
import io.oryxos.web.controller.dto.SkillView;
import io.oryxos.web.controller.dto.UpdateSkillRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.skill.GithubFolderFetcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
 * 全局 Skill 库端点（第 32 节）：list/get/create/update/delete 薄转发给 {@link SkillService}。Agent 通过 AGENT.md 的
 * {@code skills:[名]} 引用这些 Skill，由 {@code ContextLoader} 注入正文来约束产出。
 *
 * <p>错误码复用既有：name 冲突 / 空 → 400（`IllegalArgumentException`）；不存在 →
 * 404（`ResourceNotFoundException`）；统一 `ApiResponse` 信封。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2", "URLCONNECTION_SSRF_FD"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. 协作者是 Spring 注入的共享单例，构造注入共享同一引用正是意图。/import 拉取运营者给定的 URL（等同安装插件），已做 SSRF 防护：限 http/https + 超时 + 大小上限 + 禁自动重定向、每跳校验目标主机非回环/内网/链路本地(含云元数据 169.254.169.254)/CGNAT。")
@RestController
@RequestMapping("/api/v1/skills")
public class SkillApiController {

  private static final int MAX_SKILL_BYTES = 512 * 1024;
  private static final int MAX_REDIRECTS = 5;

  private final SkillService skills;

  public SkillApiController(SkillService skills) {
    this.skills = skills;
  }

  @GetMapping
  public ApiResponse<List<SkillView>> list() {
    return ApiResponse.ok(skills.list().stream().map(SkillView::from).toList());
  }

  @GetMapping("/{name}")
  public ApiResponse<SkillView> get(@PathVariable String name) {
    return ApiResponse.ok(
        skills
            .get(name)
            .map(SkillView::from)
            .orElseThrow(() -> new ResourceNotFoundException("Skill 不存在: " + name)));
  }

  @PostMapping
  public ApiResponse<SkillView> create(@RequestBody CreateSkillRequest req) {
    if (req == null || req.name() == null || req.name().isBlank()) {
      throw new IllegalArgumentException("Skill 名为空");
    }
    return ApiResponse.ok(SkillView.from(skills.create(req.name(), req.description(), req.body())));
  }

  /**
   * 从 GitHub 拉取 Skill：给一个 GitHub 目录 URL（如 {@code
   * https://github.com/obra/superpowers/tree/main/skills/brainstorming}），递归拉下该目录下全部文件（SKILL.md +
   * 脚本/参考资料等）原样落盘——不是抓网页正文，只支持 GitHub 目录，同名 → 400。
   */
  @PostMapping("/import")
  public ApiResponse<SkillView> importSkill(@RequestBody ImportSkillRequest req) {
    if (req == null || req.url() == null || req.url().isBlank()) {
      throw new IllegalArgumentException("url 为空");
    }
    GithubFolderFetcher.Target target = GithubFolderFetcher.parseTreeUrl(req.url().strip());
    Map<String, String> files =
        new GithubFolderFetcher(SkillApiController::fetch).fetchFolder(target);
    return ApiResponse.ok(
        SkillView.from(skills.importFiles(req.name(), files, target.fallbackName())));
  }

  private static URI parseHttpUrl(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("非法 URL: " + url);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"http".equals(scheme) && !"https".equals(scheme)) {
      throw new IllegalArgumentException("仅支持 http/https URL: " + url);
    }
    return uri;
  }

  /**
   * 拉取 URL 文本，带 SSRF 防护：禁自动重定向，手动最多跟 {@value #MAX_REDIRECTS} 跳，**每一跳都重新校验目标主机不是内网/回环/链路本地/元数据地址**
   * （169.254.169.254 属链路本地、已覆盖）。这样 URL 本身或其重定向都无法把服务端引向内网服务或云元数据。
   */
  private static String fetch(URI initial) {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    URI uri = initial;
    for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
      guardPublicHost(uri); // 每跳都校验（防重定向绕过）
      HttpRequest request =
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET().build();
      HttpResponse<String> resp;
      try {
        resp = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new UncheckedIOException("拉取 URL 失败: " + uri, e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("拉取被中断: " + uri, e);
      }
      int status = resp.statusCode();
      if (status / 100 == 2) {
        String body = resp.body();
        if (body != null && body.length() > MAX_SKILL_BYTES) {
          throw new IllegalArgumentException("SKILL.md 过大（>512KB），拒绝导入");
        }
        return body;
      }
      if (status / 100 == 3) {
        String location = resp.headers().firstValue("location").orElse(null);
        if (location == null || location.isBlank()) {
          throw new IllegalArgumentException("重定向缺少 Location: " + uri);
        }
        uri = parseHttpUrl(uri.resolve(location).toString()); // 解析相对地址并重新校验 scheme
        continue;
      }
      throw new IllegalArgumentException("拉取失败，HTTP " + status + ": " + uri);
    }
    throw new IllegalArgumentException("重定向次数过多，拒绝导入");
  }

  /**
   * SSRF 防护：拒绝主机解析到回环/任意本地/链路本地(含 169.254.169.254)/站点内网/组播/CGNAT，以及 localhost、*.internal、云元数据主机名。
   */
  private static void guardPublicHost(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("URL 缺少主机名: " + uri);
    }
    String h = host.toLowerCase(Locale.ROOT);
    if (h.equals("localhost") || h.equals("metadata.google.internal") || h.endsWith(".internal")) {
      throw new IllegalArgumentException("拒绝访问内网 / 元数据主机: " + host);
    }
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("无法解析主机: " + host);
    }
    for (InetAddress addr : addresses) {
      if (addr.isLoopbackAddress()
          || addr.isAnyLocalAddress()
          || addr.isLinkLocalAddress()
          || addr.isSiteLocalAddress()
          || addr.isMulticastAddress()
          || isCarrierGradeNat(addr)) {
        throw new IllegalArgumentException(
            "拒绝访问内网 / 保留地址: " + host + " → " + addr.getHostAddress());
      }
    }
  }

  /** 100.64.0.0/10（运营商级 NAT，isSiteLocalAddress 不覆盖，单独判）。 */
  private static boolean isCarrierGradeNat(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
  }

  @PutMapping("/{name}")
  public ApiResponse<SkillView> update(
      @PathVariable String name, @RequestBody UpdateSkillRequest req) {
    if (skills.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Skill 不存在: " + name); // → 404
    }
    String description = req == null ? null : req.description();
    String body = req == null ? null : req.body();
    return ApiResponse.ok(SkillView.from(skills.update(name, description, body)));
  }

  @DeleteMapping("/{name}")
  public ApiResponse<Void> delete(@PathVariable String name) {
    if (skills.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Skill 不存在: " + name); // → 404
    }
    skills.delete(name);
    return ApiResponse.ok(null);
  }
}
