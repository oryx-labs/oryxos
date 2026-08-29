package io.oryxos.web.controller;

import io.oryxos.core.skill.AgentSkillBindingService;
import io.oryxos.core.skill.SkillCatalog;
import io.oryxos.core.skill.SkillCatalogEntry;
import io.oryxos.core.skill.SkillService;
import io.oryxos.web.common.ApiResponse;
import io.oryxos.web.controller.dto.CreateSkillRequest;
import io.oryxos.web.controller.dto.ImportSkillRequest;
import io.oryxos.web.controller.dto.SkillArchiveView;
import io.oryxos.web.controller.dto.SkillBindingIssueView;
import io.oryxos.web.controller.dto.SkillCatalogView;
import io.oryxos.web.controller.dto.SkillView;
import io.oryxos.web.controller.dto.UpdateSkillRequest;
import io.oryxos.web.error.ResourceNotFoundException;
import io.oryxos.web.skill.GithubFolderFetcher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.IDN;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局 Skill 库端点：管理已安装实体、查询外部候选目录并报告 Agent 软连接绑定问题。绑定只认 Agent 目录里的固定相对软连接； 运行时仅注入 Skill 元数据与本地入口，正文由
 * Agent 按需读取。
 *
 * <p>错误码复用既有：name 冲突 / 空 → 400（`IllegalArgumentException`）；不存在 →
 * 404（`ResourceNotFoundException`）；统一 `ApiResponse` 信封。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"SPRING_ENDPOINT", "EI_EXPOSE_REP2", "URLCONNECTION_SSRF_FD", "IMPROPER_UNICODE"},
    justification =
        "core-stage web API is unauthenticated by design (internal network + gateway); auth is extension-phase. 协作者是 Spring 注入的共享单例，构造注入共享同一引用正是意图。/import 拉取运营者给定的 URL（等同安装插件），已做 SSRF 防护：限 http/https + 超时 + 大小上限 + 禁自动重定向、每跳校验目标主机非回环/内网/链路本地(含云元数据 169.254.169.254)/CGNAT/IPv6 ULA。")
@RestController
@RequestMapping("/api/v1/skills")
public class SkillApiController {

  private static final int MAX_SKILL_BYTES = 512 * 1024;
  private static final int MAX_REDIRECTS = 5;
  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";
  private static final String LOCALHOST = "localhost";
  private static final String GOOGLE_METADATA_HOST = "metadata.google.internal";
  private static final String INTERNAL_DOMAIN_SUFFIX = ".internal";
  private static final String VISIBILITY_ALL = "all";
  private static final String VISIBILITY_PUBLIC = "public";
  private static final String VISIBILITY_PRIVATE = "private";

  /** IPv6 地址字节长度；IPv4-mapped / NAT64 展开前需先确认。 */
  private static final int IPV6_ADDRESS_LENGTH = 16;

  /** {@code ::ffff:0:0/96} 前缀中必须为 0 的前缀字节数（随后两字节为 0xff）。 */
  private static final int IPV4_MAPPED_ZERO_PREFIX_LENGTH = 10;

  private static final int EMBEDDED_IPV4_TAIL_OFFSET = 12;
  private static final int SIXTOFOUR_IPV4_OFFSET = 2;
  private static final int IPV4_OCTET_COUNT = 4;
  private static final byte IPV6_LOOPBACK_SUFFIX = 1;

  private static final byte[] NO_EMBEDDED_IPV4 = new byte[0];

  private final SkillService skills;
  private final SkillCatalog catalog;
  private final AgentSkillBindingService bindings;

  public SkillApiController(SkillService skills) {
    this(skills, null, null);
  }

  @Autowired
  public SkillApiController(
      SkillService skills, SkillCatalog catalog, AgentSkillBindingService bindings) {
    this.skills = skills;
    this.catalog = catalog;
    this.bindings = bindings;
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
    if (!HTTP_SCHEME.equals(scheme) && !HTTPS_SCHEME.equals(scheme)) {
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
   * SSRF 防护：拒绝主机解析到回环/任意本地/链路本地(含 169.254.169.254)/站点内网/组播/CGNAT/IPv6 ULA （fc00::/7），以及
   * localhost、*.internal、云元数据主机名。与工具层 {@code WhitelistSandbox} 内网口径对齐。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "IDN.toASCII canonicalizes the complete DNS host before security checks; no substring is transformed independently.")
  static void guardPublicHost(URI uri) {
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new IllegalArgumentException("URL 缺少主机名: " + uri);
    }
    String asciiHost;
    try {
      asciiHost = IDN.toASCII(host);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("非法主机名: " + host);
    }
    if (isInternalName(asciiHost)) {
      throw new IllegalArgumentException("拒绝访问内网 / 元数据主机: " + host);
    }
    // IPv6 字面量偶发带方括号；剥掉后再解析，ULA/回环判断才生效（与 WhitelistSandbox 一致）
    String lookup =
        asciiHost.startsWith("[") && asciiHost.endsWith("]")
            ? asciiHost.substring(1, asciiHost.length() - 1)
            : asciiHost;
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(lookup);
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException("无法解析主机: " + host);
    }
    for (InetAddress addr : addresses) {
      if (isBlockedSsrfAddress(addr)) {
        throw new IllegalArgumentException(
            "拒绝访问内网 / 保留地址: " + host + " → " + addr.getHostAddress());
      }
    }
  }

  /**
   * IPv4-mapped / NAT64 / 6to4 / Teredo / ISATAP / IPv4-compatible 先展开嵌入 IPv4，再套用内网/元数据判定；与 {@code
   * WhitelistSandbox} 读路径 SSRF 兜底对齐。
   */
  private static boolean isBlockedSsrfAddress(InetAddress addr) {
    InetAddress effective = unwrapEmbeddedIpv4(addr);
    return addr.isLoopbackAddress()
        || addr.isAnyLocalAddress()
        || effective.isLoopbackAddress()
        || effective.isAnyLocalAddress()
        || effective.isLinkLocalAddress()
        || effective.isSiteLocalAddress()
        || effective.isMulticastAddress()
        || isCarrierGradeNat(effective)
        || isIpv6UniqueLocal(addr);
  }

  private static InetAddress unwrapEmbeddedIpv4(InetAddress addr) {
    byte[] b = addr.getAddress();
    if (b.length != IPV6_ADDRESS_LENGTH) {
      return addr;
    }
    byte[] ipv4 = extractEmbeddedIpv4(b);
    if (ipv4.length == 0) {
      return addr;
    }
    try {
      return InetAddress.getByAddress(ipv4);
    } catch (UnknownHostException e) {
      return addr;
    }
  }

  private static byte[] extractEmbeddedIpv4(byte[] b) {
    if (isIpv4MappedPrefix(b) || isNat64WellKnownPrefix(b) || isIpv4CompatiblePrefix(b)) {
      return new byte[] {
        b[EMBEDDED_IPV4_TAIL_OFFSET],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 1],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 2],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 3]
      };
    }
    if (isSixToFourPrefix(b)) {
      return new byte[] {
        b[SIXTOFOUR_IPV4_OFFSET],
        b[SIXTOFOUR_IPV4_OFFSET + 1],
        b[SIXTOFOUR_IPV4_OFFSET + 2],
        b[SIXTOFOUR_IPV4_OFFSET + 3]
      };
    }
    if (isTeredoPrefix(b)) {
      return new byte[] {
        (byte) (~b[EMBEDDED_IPV4_TAIL_OFFSET] & 0xFF),
        (byte) (~b[EMBEDDED_IPV4_TAIL_OFFSET + 1] & 0xFF),
        (byte) (~b[EMBEDDED_IPV4_TAIL_OFFSET + 2] & 0xFF),
        (byte) (~b[EMBEDDED_IPV4_TAIL_OFFSET + 3] & 0xFF)
      };
    }
    if (isIsatapInterfaceId(b)) {
      return new byte[] {
        b[EMBEDDED_IPV4_TAIL_OFFSET],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 1],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 2],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 3]
      };
    }
    return NO_EMBEDDED_IPV4;
  }

  private static boolean isIpv4MappedPrefix(byte[] b) {
    for (int i = 0; i < IPV4_MAPPED_ZERO_PREFIX_LENGTH; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
  }

  private static boolean isNat64WellKnownPrefix(byte[] b) {
    return (b[0] & 0xFF) == 0x00
        && (b[1] & 0xFF) == 0x64
        && (b[2] & 0xFF) == 0xFF
        && (b[3] & 0xFF) == 0x9B
        && b[4] == 0
        && b[5] == 0
        && b[6] == 0
        && b[7] == 0
        && b[8] == 0
        && b[9] == 0
        && b[10] == 0
        && b[11] == 0;
  }

  private static boolean isSixToFourPrefix(byte[] b) {
    return (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02;
  }

  private static boolean isIpv4CompatiblePrefix(byte[] b) {
    for (int i = 0; i < IPV4_MAPPED_ZERO_PREFIX_LENGTH; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    if (b[IPV4_MAPPED_ZERO_PREFIX_LENGTH] != 0 || b[IPV4_MAPPED_ZERO_PREFIX_LENGTH + 1] != 0) {
      return false;
    }
    return !isNativeIpv6UnspecifiedOrLoopbackTail(b);
  }

  private static boolean isNativeIpv6UnspecifiedOrLoopbackTail(byte[] b) {
    int lastIndex = EMBEDDED_IPV4_TAIL_OFFSET + IPV4_OCTET_COUNT - 1;
    for (int i = EMBEDDED_IPV4_TAIL_OFFSET; i < lastIndex; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    byte last = b[lastIndex];
    return last == 0 || last == IPV6_LOOPBACK_SUFFIX;
  }

  /** Teredo {@code 2001:0000::/32}（RFC 4380）。 */
  private static boolean isTeredoPrefix(byte[] b) {
    return (b[0] & 0xFF) == 0x20
        && (b[1] & 0xFF) == 0x01
        && (b[2] & 0xFF) == 0x00
        && (b[3] & 0xFF) == 0x00;
  }

  /** ISATAP IID {@code 0000:5EFE} / {@code 0200:5EFE}（RFC 5214 §6.1）。 */
  private static boolean isIsatapInterfaceId(byte[] b) {
    int b8 = b[8] & 0xFF;
    return (b8 == 0x00 || b8 == 0x02)
        && b[9] == 0
        && (b[10] & 0xFF) == 0x5E
        && (b[11] & 0xFF) == 0xFE;
  }

  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "DNS labels are case-insensitive and the complete IDN-canonicalized host is compared.")
  private static boolean isInternalName(String host) {
    if (LOCALHOST.equalsIgnoreCase(host) || GOOGLE_METADATA_HOST.equalsIgnoreCase(host)) {
      return true;
    }
    int offset = host.length() - INTERNAL_DOMAIN_SUFFIX.length();
    return offset >= 0
        && host.regionMatches(
            true, offset, INTERNAL_DOMAIN_SUFFIX, 0, INTERNAL_DOMAIN_SUFFIX.length());
  }

  /** 100.64.0.0/10（运营商级 NAT，isSiteLocalAddress 不覆盖，单独判）。 */
  private static boolean isCarrierGradeNat(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
  }

  /** IPv6 ULA fc00::/7（唯一本地地址，isSiteLocalAddress 对 IPv6 不覆盖——否则 [fd00::1] 可绕过）。 */
  private static boolean isIpv6UniqueLocal(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == IPV6_ADDRESS_LENGTH && (b[0] & 0xFE) == 0xFC;
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
  public ApiResponse<SkillArchiveView> delete(@PathVariable String name) {
    if (skills.get(name).isEmpty()) {
      throw new ResourceNotFoundException("Skill 不存在: " + name); // → 404
    }
    return ApiResponse.ok(SkillArchiveView.from(skills.delete(name)));
  }

  @GetMapping("/catalog")
  public ApiResponse<List<SkillCatalogView>> catalog(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "all") String visibility) {
    if (catalog == null) {
      throw new IllegalStateException("Skill catalog 不可用");
    }
    SkillCatalogEntry.Visibility filter;
    if (VISIBILITY_ALL.equals(visibility)) {
      filter = null;
    } else if (VISIBILITY_PUBLIC.equals(visibility)) {
      filter = SkillCatalogEntry.Visibility.PUBLIC;
    } else if (VISIBILITY_PRIVATE.equals(visibility)) {
      filter = SkillCatalogEntry.Visibility.PRIVATE;
    } else {
      throw new IllegalArgumentException("非法 visibility: " + visibility);
    }
    return ApiResponse.ok(catalog.query(q, filter).stream().map(SkillCatalogView::from).toList());
  }

  @GetMapping("/binding-issues")
  public ApiResponse<List<SkillBindingIssueView>> bindingIssues() {
    if (bindings == null) {
      throw new IllegalStateException("Agent Skill 绑定服务未装配");
    }
    return ApiResponse.ok(bindings.reconcile().stream().map(SkillBindingIssueView::from).toList());
  }
}
