package io.oryxos.tool.sandbox;

import io.oryxos.core.fs.RealPathBoundary;
import io.oryxos.core.sandbox.SandboxWhitelist;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 核心阶段唯一的 {@link Sandbox} 实现：应用层白名单校验（宪法 VI 第一档）。按 {@link ActionType} 路由到文件路径 / 可执行文件 / HTTP 域名 /
 * SMTP 端点四类校验，任一不过抛 {@link SandboxViolationException}、动作零发生。
 *
 * <p>四块白名单初始来自配置（{@code file.allowed_paths} / {@code shell.allowed_commands} / {@code
 * http.allowed_domains} / {@code smtp.allowed_endpoints}）。空列表天然 deny-all（{@code anyMatch} 对空流恒
 * false），配置缺失绝不退化为放行。
 *
 * <p>同时实现 {@link SandboxWhitelist}：管理员可经 Web 端点运行时查询 / 增删白名单。存储用并发集合 （{@link CopyOnWriteArrayList}
 * / {@link ConcurrentHashMap#newKeySet()}）——校验读路径无锁（热路径）， 管理写路径极少发生、拷贝开销可接受；非异步编程模型，符合宪法 VII。每次改动落
 * INFO 日志留痕。
 *
 * <p>四个 {@code check*} 与 {@code matchesDomain} 均 {@code private}——对外只暴露 {@code enforce} 与管理三方法。 若把
 * check* public 暴露到 {@code Sandbox} 接口上，接口就被这一档实现带偏了。
 */
// final：构造器会因非法配置抛异常（normalizeRoot/requireNonBlank），禁止子类化以杜绝 finalizer attack（CT_CONSTRUCTOR_THROW）
public final class WhitelistSandbox implements Sandbox, SandboxWhitelist {

  private static final Logger LOG = LoggerFactory.getLogger(WhitelistSandbox.class);

  /** 域名白名单里的通配前缀；命中后转成"以 . 之后部分结尾"的点号边界匹配。 */
  private static final String WILDCARD_PREFIX = "*.";

  private static final String LOCALHOST = "localhost";
  private static final String GOOGLE_METADATA_HOST = "metadata.google.internal";
  private static final String INTERNAL_DOMAIN_SUFFIX = ".internal";
  private static final String HTTP_SCHEME = "http";
  private static final String HTTPS_SCHEME = "https";

  /** {@link io.oryxos.tool.builtin.WebSearchTools} 用的伪目标前缀；无真实主机，读路径放行。 */
  private static final String WEB_SEARCH_TARGET_PREFIX = "web_search:";

  /** IPv6 地址字节长度；IPv4-mapped / NAT64 展开前需先确认。 */
  private static final int IPV6_ADDRESS_LENGTH = 16;

  /** {@code ::ffff:0:0/96} 前缀中必须为 0 的前缀字节数（随后两字节为 0xff）。 */
  private static final int IPV4_MAPPED_ZERO_PREFIX_LENGTH = 10;

  /** IPv4-mapped / NAT64 / IPv4-compatible：嵌入 IPv4 起始下标（末 4 字节）。 */
  private static final int EMBEDDED_IPV4_TAIL_OFFSET = 12;

  /** 6to4：嵌入 IPv4 起始下标（字节 2–5）。 */
  private static final int SIXTOFOUR_IPV4_OFFSET = 2;

  private static final int IPV4_OCTET_COUNT = 4;

  /** 原生 IPv6 {@code ::1} 的末字节。 */
  private static final byte IPV6_LOOPBACK_SUFFIX = 1;

  private static final byte[] NO_EMBEDDED_IPV4 = new byte[0];

  // 具体类型 CopyOnWriteArrayList（而非 List 接口）：需要 addIfAbsent 的原子"不存在才加"语义
  private final CopyOnWriteArrayList<Path> allowedRoots = new CopyOnWriteArrayList<>();
  private final Set<String> allowedCommands = ConcurrentHashMap.newKeySet();
  private final CopyOnWriteArrayList<String> allowedDomainPatterns = new CopyOnWriteArrayList<>();
  private final CopyOnWriteArrayList<String> allowedSmtpEndpoints = new CopyOnWriteArrayList<>();

  // 持久化后端（31 节）：非空则 add/remove 写穿落库、构造时从库恢复；为 null 时纯内存（单测 / 无库场景）。
  private final SandboxWhitelistStore store;

  /**
   * 纯内存构造：三块白名单来自配置。null（配置键缺省）归一为空 = deny-all，绝不 NPE 也绝不放行。 根目录归一为绝对路径（{@code checkFilePath} 对
   * target 做 {@code toAbsolutePath()}，根也须绝对化才能对称比对）。
   */
  public WhitelistSandbox(
      FileSandboxProperties fileProps,
      ShellSandboxProperties shellProps,
      HttpSandboxProperties httpProps) {
    this.store = null;
    nullToEmpty(fileProps.allowedPaths()).forEach(p -> applyToMemory(Category.FILE, p));
    nullToEmpty(shellProps.allowedCommands()).forEach(c -> applyToMemory(Category.SHELL, c));
    nullToEmpty(httpProps.allowedDomains()).forEach(d -> applyToMemory(Category.HTTP, d));
  }

  /**
   * 持久化构造（31 节）：从 {@link SandboxWhitelistStore} 恢复已落库的三类白名单；之后 {@code add}/{@code remove}
   * 写穿到库、重启保留。启动播种（把配置文件的白名单插进来）由装配层调用 {@code add} 完成——{@code add} 会算好规范形并幂等落库。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = {"EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW"},
      justification =
          "store 是 Spring 注入的共享单例仓库，构造注入共享同一引用正是意图；"
              + "构造期 applyToMemory 校验失败应失败关闭，不保留半初始化实例供 finalize 攻击")
  public WhitelistSandbox(SandboxWhitelistStore store) {
    this.store = store;
    for (SandboxWhitelistStore.Entry entry : store.loadAll()) {
      applyToMemory(entry.category(), entry.value());
    }
  }

  /** 仅更新内存（不写库）：构造 / 恢复时用。FILE 归一为绝对路径。 */
  private void applyToMemory(Category category, String value) {
    if (category == Category.FILE) {
      allowedRoots.addIfAbsent(normalizeRoot(value));
    } else if (category == Category.SHELL) {
      allowedCommands.add(requireNonBlank(value));
    } else if (category == Category.HTTP) {
      allowedDomainPatterns.addIfAbsent(value);
    } else if (category == Category.SMTP) {
      allowedSmtpEndpoints.addIfAbsent(value);
    }
  }

  private static List<String> nullToEmpty(List<String> list) {
    return list == null ? List.of() : list;
  }

  private static Path normalizeRoot(String rawPath) {
    Path lexical = lexicalRoot(rawPath);
    try {
      return RealPathBoundary.project(lexical).projectedReal();
    } catch (RuntimeException e) {
      LOG.warn("白名单路径暂时无法解析真实目标，保留词法路径并在访问时失败关闭: {}", sanitize(lexical.toString()));
      return lexical;
    }
  }

  private static Path lexicalRoot(String rawPath) {
    return Path.of(rawPath).toAbsolutePath().normalize();
  }

  @Override
  public void enforce(SandboxAction action) {
    // 传统 switch（colon + break + default）：P3C SwitchStatementRule 只认这一形态的 default，
    // 增强 switch 的 default -> 会被判"缺 default"（语法禁区，静态检查是构建门禁）
    switch (action.type()) {
      case FILE_READ:
      case FILE_WRITE:
        checkFilePath(action.target());
        break;
      case SHELL_COMMAND:
        checkShellCommand(action.target());
        break;
      case HTTP_READ:
        checkHttpRead(action.target());
        break;
      case HTTP_REQUEST:
        checkHttpWrite(action.target());
        break;
      case SMTP_SEND:
        checkSmtpEndpoint(action.target());
        break;
      default:
        // 安全默认：未来若新增未覆盖的动作类型，deny 而非静默放行（宪法 VI）
        throw new SandboxViolationException("未知的沙箱动作类型: " + action.type());
    }
  }

  private void checkFilePath(String rawPath) {
    Path target;
    try {
      target = RealPathBoundary.project(Path.of(rawPath)).projectedReal();
    } catch (RuntimeException e) {
      throw new SandboxViolationException("路径真实目标无法安全解析，拒绝访问: " + rawPath);
    }
    boolean allowed = allowedRoots.stream().anyMatch(target::startsWith);
    if (!allowed) {
      throw new SandboxViolationException(
          "路径不在白名单内: "
              + rawPath
              + "。这是安全策略，请勿反复重试；Agent 产出请写到工作区（"
              + firstAllowedRoot()
              + " 下，如 <该 Agent 目录>/output/）。确需读写别处，请在管理台「SandBox 列表」把该路径加入 file 白名单。");
    }
  }

  private void checkShellCommand(String command) {
    if (!allowedCommands.contains(command)) {
      throw new SandboxViolationException("可执行文件不在白名单内: " + command);
    }
  }

  /**
   * HTTP 读（GET 类）：默认放行，只挡内网/回环/云元数据等 SSRF 目标。仅 {@code web_search:} 伪目标可无主机；其余必须是 http/https
   * 且带主机名——否则 {@code file://}/{@code data:} 等会因 host==null 被误放行。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI scheme tokens are ASCII; Locale.ROOT lowercasing is the correct case-fold for http/https comparison.")
  private void checkHttpRead(String url) {
    if (url != null && url.startsWith(WEB_SEARCH_TARGET_PREFIX)) {
      return;
    }
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      throw new SandboxViolationException("非法 URL: " + url);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!HTTP_SCHEME.equals(scheme) && !HTTPS_SCHEME.equals(scheme)) {
      throw new SandboxViolationException(
          "读请求仅支持 http/https（伪目标 web_search: 除外）: " + url + "。这是安全策略，请勿重试。");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SandboxViolationException("读请求缺少主机名，拒绝: " + url);
    }
    assertNotInternalHost(host);
  }

  /**
   * HTTP 写（POST/PUT/…）：过域名白名单——防止把数据外发到任意端点。必须先是 http/https（带主机名），再匹配白名单——否则 {@code
   * ftp://api.deepseek.com/…} / {@code file://api.deepseek.com/…} 会因 host 命中白名单被误放行。白名单本身即"运营者批准的
   * HTTP(S) 目标"，故不再叠加 SSRF 解析（内网 POST 需运营者显式白名单，属其决定）；SSRF 兜底集中在默认放行的 READ 路径。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "URI scheme tokens are ASCII; Locale.ROOT lowercasing is the correct case-fold for http/https comparison.")
  private void checkHttpWrite(String url) {
    URI uri;
    try {
      uri = URI.create(url);
    } catch (RuntimeException e) {
      throw new SandboxViolationException("非法 URL: " + url);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!HTTP_SCHEME.equals(scheme) && !HTTPS_SCHEME.equals(scheme)) {
      throw new SandboxViolationException("写请求仅支持 http/https: " + url + "。这是安全策略（防数据外发），请勿重试。");
    }
    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new SandboxViolationException("写请求缺少主机名，拒绝: " + url);
    }
    boolean allowed =
        allowedDomainPatterns.stream().anyMatch(pattern -> matchesDomain(host, pattern));
    if (!allowed) {
      throw new SandboxViolationException(
          "写请求(POST/PUT 等)目标不在出网白名单: "
              + host
              + "。这是安全策略（防数据外发），请勿反复重试；确需向该地址发送数据，请在管理台「SandBox 列表」把该域名加入 http 白名单后再试。");
    }
  }

  /**
   * SMTP 发信（非 HTTP 出站）：过端点白名单——按 host[:port] 精确放行，端口不被忽略（区别于 {@link #checkHttpWrite} 只校验域名）。
   * 白名单条目形如 {@code host}（任意端口）或 {@code host:port}（指定端口），域名部分支持 {@code *.} 通配。
   */
  private void checkSmtpEndpoint(String hostPort) {
    int colon = hostPort == null ? -1 : hostPort.lastIndexOf(':');
    String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
    String port = colon < 0 ? null : hostPort.substring(colon + 1);
    if (host == null || host.isBlank()) {
      throw new SandboxViolationException("SMTP 目标缺少主机名，拒绝: " + hostPort);
    }
    boolean allowed = allowedSmtpEndpoints.stream().anyMatch(p -> matchesSmtp(p, host, port));
    if (!allowed) {
      throw new SandboxViolationException(
          "SMTP 目标不在出网白名单: "
              + hostPort
              + "。这是安全策略（防数据外发），请勿反复重试；确需向该邮件服务器发信，请在管理台「SandBox 列表」把该 smtp 端点（host[:port]）加入 smtp 白名单后再试。");
    }
  }

  /** 端点匹配：域名部分复用 {@link #matchesDomain}（大小写不敏感 + {@code *.} 通配）；带端口的条目还需端口相等。 */
  private boolean matchesSmtp(String pattern, String host, String targetPort) {
    int colon = pattern.lastIndexOf(':');
    String patternHost = colon < 0 ? pattern : pattern.substring(0, colon);
    String patternPort = colon < 0 ? null : pattern.substring(colon + 1);
    if (!matchesDomain(host, patternHost)) {
      return false;
    }
    return patternPort == null || patternPort.equals(targetPort);
  }

  /** SSRF 兜底：拒绝主机解析到回环/任意本地/链路本地(含云元数据 169.254.169.254)/站点内网/组播/CGNAT，及 localhost、*.internal。 */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "IDN.toASCII canonicalizes the complete DNS host before security checks; no substring is transformed independently.")
  private static void assertNotInternalHost(String host) {
    String asciiHost;
    try {
      asciiHost = IDN.toASCII(host);
    } catch (IllegalArgumentException e) {
      throw new SandboxViolationException("非法主机名: " + host);
    }
    if (isInternalName(asciiHost)) {
      throw new SandboxViolationException("拒绝访问内网 / 元数据主机（SSRF 防护）: " + host + "。这是安全策略，请勿重试。");
    }
    // IPv6 字面量 getHost() 带方括号（如 [fd00::1]），解析前剥掉，ULA/回环等判断才生效
    String lookup =
        asciiHost.startsWith("[") && asciiHost.endsWith("]")
            ? asciiHost.substring(1, asciiHost.length() - 1)
            : asciiHost;
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(lookup);
    } catch (UnknownHostException e) {
      throw new SandboxViolationException("无法解析主机: " + host + "。请检查地址是否正确，勿反复重试。");
    }
    for (InetAddress addr : addresses) {
      if (isBlockedSsrfAddress(addr)) {
        throw new SandboxViolationException(
            "拒绝访问内网 / 保留地址（SSRF 防护）: " + host + " → " + addr.getHostAddress() + "。这是安全策略，请勿重试。");
      }
    }
  }

  /**
   * 对解析结果做 SSRF 分类。IPv4-mapped（{@code ::ffff:0:0/96}）、NAT64 知名前缀（{@code 64:ff9b::/96}）、6to4（{@code
   * 2002::/16}）、Teredo（{@code 2001:0000::/32}）、ISATAP（IID {@code 0000:5EFE}/{@code 0200:5EFE}）与已弃用的
   * IPv4-compatible（{@code ::/96}）先展开嵌入 IPv4，再套用回环/链路本地/站点内网/CGNAT 等判定。
   */
  private static boolean isBlockedSsrfAddress(InetAddress addr) {
    InetAddress effective = unwrapEmbeddedIpv4(addr);
    // addr 侧保留原生 IPv6 回环/未指定（避免 ::1 被误展开成 0.0.0.1 后漏拦）
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

  /**
   * 若为 IPv4-mapped / NAT64 / 6to4 / Teredo / ISATAP / IPv4-compatible，返回嵌入的 IPv4；否则原样返回。JDK 常把
   * mapped 字面量直接解成 {@link java.net.Inet4Address}，此展开主要兜住仍以 16 字节返回的形态与隧道前缀。
   */
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
      return addr; // 4 字节形式不会失败；保底不改变判定输入
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
      // RFC 5214：IID 0000:5EFE / 0200:5EFE，IPv4 在末 32 位（不取反）
      return new byte[] {
        b[EMBEDDED_IPV4_TAIL_OFFSET],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 1],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 2],
        b[EMBEDDED_IPV4_TAIL_OFFSET + 3]
      };
    }
    return NO_EMBEDDED_IPV4;
  }

  /** {@code ::ffff:0:0/96}——前 10 字节为 0，第 11–12 字节为 {@code 0xff}。 */
  private static boolean isIpv4MappedPrefix(byte[] b) {
    for (int i = 0; i < IPV4_MAPPED_ZERO_PREFIX_LENGTH; i++) {
      if (b[i] != 0) {
        return false;
      }
    }
    return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
  }

  /** Teredo {@code 2001:0000::/32}（RFC 4380）。 */
  private static boolean isTeredoPrefix(byte[] b) {
    return (b[0] & 0xFF) == 0x20
        && (b[1] & 0xFF) == 0x01
        && (b[2] & 0xFF) == 0x00
        && (b[3] & 0xFF) == 0x00;
  }

  /**
   * ISATAP 接口标识（RFC 5214 §6.1）：字节 8–11 为 {@code 0000:5EFE}，或 u 位置位时的 {@code 0200:5EFE}；嵌入 IPv4 在字节
   * 12–15。
   */
  private static boolean isIsatapInterfaceId(byte[] b) {
    int b8 = b[8] & 0xFF;
    return (b8 == 0x00 || b8 == 0x02)
        && b[9] == 0
        && (b[10] & 0xFF) == 0x5E
        && (b[11] & 0xFF) == 0xFE;
  }

  /** NAT64 知名前缀 {@code 64:ff9b::/96}（RFC 6052）。 */
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

  /** 6to4 {@code 2002::/16}（RFC 3056）。 */
  private static boolean isSixToFourPrefix(byte[] b) {
    return (b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02;
  }

  /**
   * 已弃用的 IPv4-compatible {@code ::/96}（RFC 4291），排除 {@code ::ffff:0:0/96} mapped，以及原生 {@code
   * ::}/{@code ::1}（否则会展开成 0.0.0.0/0.0.0.1 漏拦）。
   */
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

  /** 末 4 字节为 {@code 0.0.0.0}（{@code ::}）或 {@code 0.0.0.1}（{@code ::1}）。 */
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

  /** IPv6 ULA fc00::/7（唯一本地地址，isSiteLocalAddress 对 IPv6 不覆盖，单独判——否则 [fd00::1] 可绕过）。 */
  private static boolean isIpv6UniqueLocal(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == IPV6_ADDRESS_LENGTH && (b[0] & 0xFE) == 0xFC;
  }

  private String firstAllowedRoot() {
    return allowedRoots.isEmpty() ? ".oryxos" : allowedRoots.get(0).toString();
  }

  /**
   * 通配符匹配带点号边界：{@code *.example.com} 转成"以 {@code .example.com} 结尾"，天然挡住形似域名 {@code
   * evil-example.com}（{@code endsWith("example.com")} 的经典漏洞）与裸域 {@code example.com}，
   * 同时匹配多级真子域。非通配项按精确相等匹配。域名比对大小写不敏感。
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification =
          "域名是 ASCII，Locale.ROOT 小写化是国际化安全的正确选择；此处仅用于大小写不敏感的域名比对，不涉及会因 unicode 折叠改变语义的字段")
  private boolean matchesDomain(String host, String pattern) {
    String h = host.toLowerCase(Locale.ROOT);
    String p = pattern.toLowerCase(Locale.ROOT);
    if (p.startsWith(WILDCARD_PREFIX)) {
      return h.endsWith(p.substring(1));
    }
    return h.equals(p);
  }

  // ---- SandboxWhitelist：运行时管理（查询 / 增加 / 删除）----

  @Override
  public List<String> list(Category category) {
    if (category == Category.FILE) {
      return allowedRoots.stream().map(Path::toString).toList();
    }
    if (category == Category.SHELL) {
      return List.copyOf(allowedCommands);
    }
    if (category == Category.HTTP) {
      return List.copyOf(allowedDomainPatterns);
    }
    return List.copyOf(allowedSmtpEndpoints);
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "entry 经 sanitize() 消去 CR/LF 后才进日志；taint 分析不跨方法追踪该消毒，故局部抑制")
  public boolean add(Category category, String value) {
    String entry = requireNonBlank(value);
    boolean changed;
    String canonical; // 入内存的规范形，也是落库/展示/删除对齐的值（FILE 为归一后的绝对路径）
    String staleCanonical = null;
    if (category == Category.FILE) {
      Path lexical = lexicalRoot(entry);
      Path root = normalizeRoot(entry);
      canonical = root.toString();
      if (!root.equals(lexical) && allowedRoots.remove(lexical)) {
        allowedRoots.addIfAbsent(root);
        staleCanonical = lexical.toString();
        changed = true;
      } else {
        changed = allowedRoots.addIfAbsent(root);
      }
    } else if (category == Category.SHELL) {
      canonical = entry;
      changed = allowedCommands.add(canonical);
    } else if (category == Category.HTTP) {
      canonical = entry;
      changed = allowedDomainPatterns.addIfAbsent(entry);
    } else {
      canonical = entry;
      changed = allowedSmtpEndpoints.addIfAbsent(entry);
    }
    // 写穿：只有内存确有变更才落库（幂等，避免重复写；启动播种重复调用不会重复插入）
    if (changed && store != null) {
      if (staleCanonical != null) {
        store.remove(category, staleCanonical);
      }
      store.add(category, canonical);
    }
    LOG.info("Sandbox 白名单增加 {} -> {}（changed={}）", category, sanitize(entry), changed);
    return changed;
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "entry 经 sanitize() 消去 CR/LF 后才进日志；taint 分析不跨方法追踪该消毒，故局部抑制")
  public boolean remove(Category category, String value) {
    String entry = requireNonBlank(value);
    boolean changed;
    String canonical;
    if (category == Category.FILE) {
      Path root = normalizeRoot(entry);
      Path removed = removeFileRoot(root, lexicalRoot(entry));
      canonical = removed == null ? root.toString() : removed.toString();
      changed = removed != null;
    } else if (category == Category.SHELL) {
      canonical = entry;
      changed = allowedCommands.remove(entry);
    } else if (category == Category.HTTP) {
      canonical = entry;
      changed = allowedDomainPatterns.remove(entry);
    } else {
      canonical = entry;
      changed = allowedSmtpEndpoints.remove(entry);
    }
    if (changed && store != null) {
      store.remove(category, canonical);
    }
    LOG.info("Sandbox 白名单删除 {} -> {}（changed={}）", category, sanitize(entry), changed);
    return changed;
  }

  private Path removeFileRoot(Path normalized, Path lexical) {
    if (allowedRoots.remove(normalized)) {
      return normalized;
    }
    if (!normalized.equals(lexical) && allowedRoots.remove(lexical)) {
      return lexical;
    }
    return null;
  }

  private static String requireNonBlank(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("白名单条目不能为空");
    }
    return value.strip();
  }

  /** 去掉 CR/LF，防止条目内容伪造日志行（CWE-117）。 */
  private static String sanitize(String value) {
    return value.replace('\r', '_').replace('\n', '_');
  }
}
