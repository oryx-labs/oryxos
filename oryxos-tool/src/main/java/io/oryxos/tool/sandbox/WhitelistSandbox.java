package io.oryxos.tool.sandbox;

import io.oryxos.core.sandbox.SandboxWhitelist;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 核心阶段唯一的 {@link Sandbox} 实现：应用层白名单校验（宪法 VI 第一档）。按 {@link ActionType} 路由到文件路径 / 命令首 token / HTTP
 * 域名三类校验，任一不过抛 {@link SandboxViolationException}、动作零发生。
 *
 * <p>三块白名单初始来自配置（{@code file.allowed_paths} / {@code shell.allowed_commands} / {@code
 * http.allowed_domains}）。空列表天然 deny-all（{@code anyMatch} 对空流恒 false），配置缺失绝不退化为放行。
 *
 * <p>同时实现 {@link SandboxWhitelist}：管理员可经 Web 端点运行时查询 / 增删白名单。存储用并发集合 （{@link CopyOnWriteArrayList}
 * / {@link ConcurrentHashMap#newKeySet()}）——校验读路径无锁（热路径）， 管理写路径极少发生、拷贝开销可接受；非异步编程模型，符合宪法 VII。每次改动落
 * INFO 日志留痕。
 *
 * <p>三个 {@code check*} 与 {@code matchesDomain} 均 {@code private}——对外只暴露 {@code enforce} 与管理三方法。 若把
 * check* public 暴露到 {@code Sandbox} 接口上，接口就被这一档实现带偏了。
 */
public class WhitelistSandbox implements Sandbox, SandboxWhitelist {

  private static final Logger LOG = LoggerFactory.getLogger(WhitelistSandbox.class);

  /** 域名白名单里的通配前缀；命中后转成"以 . 之后部分结尾"的点号边界匹配。 */
  private static final String WILDCARD_PREFIX = "*.";

  /** bash 会解释为控制语法或展开语法的字符；白名单只允许单条简单命令。 */
  private static final String FORBIDDEN_SHELL_CHARACTERS = ";|&<>`$";

  private static final String SHELL_COMMAND_LONG_OPTION = "--command";
  private static final String INTERNAL_DOMAIN_SUFFIX = ".internal";

  private static final Set<String> SHELL_INTERPRETERS = Set.of("sh", "bash", "dash", "ksh", "zsh");
  private static final Set<String> SHELL_EXECUTION_WRAPPERS =
      Set.of("command", "env", "nice", "nohup", "sudo", "timeout", "xargs");
  private static final Set<String> INTERNAL_HOSTS = Set.of("localhost", "metadata.google.internal");

  // 具体类型 CopyOnWriteArrayList（而非 List 接口）：需要 addIfAbsent 的原子"不存在才加"语义
  private final CopyOnWriteArrayList<Path> allowedRoots = new CopyOnWriteArrayList<>();
  private final Set<String> allowedCommands = ConcurrentHashMap.newKeySet();
  private final CopyOnWriteArrayList<String> allowedDomainPatterns = new CopyOnWriteArrayList<>();

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
      value = "EI_EXPOSE_REP2",
      justification = "store 是 Spring 注入的共享单例仓库，构造注入共享同一引用正是意图")
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
      allowedCommands.add(value);
    } else {
      allowedDomainPatterns.addIfAbsent(value);
    }
  }

  private static List<String> nullToEmpty(List<String> list) {
    return list == null ? List.of() : list;
  }

  private static Path normalizeRoot(String rawPath) {
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
      default:
        // 安全默认：未来若新增未覆盖的动作类型，deny 而非静默放行（宪法 VI）
        throw new SandboxViolationException("未知的沙箱动作类型: " + action.type());
    }
  }

  private void checkFilePath(String rawPath) {
    if (rawPath == null) {
      throw new SandboxViolationException("文件路径非法");
    }
    Path target;
    try {
      target = Path.of(rawPath).toAbsolutePath();
    } catch (InvalidPathException e) {
      throw new SandboxViolationException("文件路径非法");
    }
    rejectParentTraversal(target);
    Path normalizedTarget = target.normalize();
    Path resolvedTarget = resolveForContainment(normalizedTarget, rawPath);
    boolean allowed =
        allowedRoots.stream()
            .anyMatch(
                root ->
                    normalizedTarget.startsWith(root)
                        && resolvedTarget.startsWith(resolveForContainment(root, rawPath)));
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
    List<String> tokens = parseSimpleCommand(command);
    String firstToken = tokens.get(0);
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException(
          "命令不在白名单内: " + firstToken + "。这是安全策略，请勿反复重试；确需该命令，请在管理台「SandBox 列表」把它加入 shell 白名单。");
    }
    String executable = executableName(firstToken);
    if (SHELL_EXECUTION_WRAPPERS.contains(executable)) {
      throw new SandboxViolationException("不允许通过命令包装器执行其他程序: " + executable);
    }
    rejectShellInterpreterCommandMode(tokens);
  }

  private void rejectParentTraversal(Path target) {
    for (Path part : target) {
      if ("..".equals(part.toString())) {
        throw new SandboxViolationException("文件路径不允许父目录跳转");
      }
    }
  }

  /** 将已存在的最长前缀解析到真实路径，再拼回尚不存在的尾部。这样既覆盖读路径，也覆盖待创建文件的写路径；任一已存在父目录是 symlink 时都会按实际目标做白名单边界判断。 */
  private Path resolveForContainment(Path path, String rawPath) {
    Path existing = path;
    List<Path> missingParts = new ArrayList<>();
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
      Path name = existing.getFileName();
      Path parent = existing.getParent();
      if (name == null || parent == null) {
        throw new SandboxViolationException("文件路径无法解析: " + rawPath);
      }
      missingParts.add(name);
      existing = parent;
    }
    try {
      Path resolved = existing.toRealPath();
      for (int i = missingParts.size() - 1; i >= 0; i--) {
        resolved = resolved.resolve(missingParts.get(i));
      }
      return resolved.normalize();
    } catch (IOException | SecurityException e) {
      throw new SandboxViolationException("文件路径无法安全解析: " + rawPath);
    }
  }

  private List<String> parseSimpleCommand(String command) {
    if (command == null || command.isBlank()) {
      throw new SandboxViolationException("命令不能为空");
    }
    for (int i = 0; i < command.length(); i++) {
      char current = command.charAt(i);
      if (Character.isISOControl(current) || FORBIDDEN_SHELL_CHARACTERS.indexOf(current) >= 0) {
        throw new SandboxViolationException("命令包含不允许的 shell 控制语法");
      }
    }

    List<String> tokens = new ArrayList<>();
    StringBuilder token = new StringBuilder();
    char quote = 0;
    boolean escaped = false;
    for (int i = 0; i < command.length(); i++) {
      char current = command.charAt(i);
      if (escaped) {
        token.append(current);
        escaped = false;
      } else if (current == '\\' && quote != '\'') {
        escaped = true;
      } else if (quote != 0) {
        if (current == quote) {
          quote = 0;
        } else {
          token.append(current);
        }
      } else if (current == '\'' || current == '"') {
        quote = current;
      } else if (Character.isWhitespace(current)) {
        addToken(tokens, token);
      } else {
        token.append(current);
      }
    }
    if (escaped || quote != 0) {
      throw new SandboxViolationException("命令包含未闭合的引用或转义");
    }
    addToken(tokens, token);
    if (tokens.isEmpty()) {
      throw new SandboxViolationException("命令不能为空");
    }
    return tokens;
  }

  private void addToken(List<String> tokens, StringBuilder token) {
    if (!token.isEmpty()) {
      tokens.add(token.toString());
      token.setLength(0);
    }
  }

  private void rejectShellInterpreterCommandMode(List<String> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      String token = tokens.get(i);
      String executable = executableName(token);
      if (!SHELL_INTERPRETERS.contains(executable)) {
        continue;
      }
      for (int j = i + 1; j < tokens.size(); j++) {
        String option = tokens.get(j);
        if (isCommandModeOption(option)) {
          throw new SandboxViolationException("不允许通过 shell 解释器命令模式执行");
        }
      }
    }
  }

  private boolean isCommandModeOption(String option) {
    if (SHELL_COMMAND_LONG_OPTION.equals(option)) {
      return true;
    }
    return option.startsWith("-") && !option.startsWith("--") && option.substring(1).contains("c");
  }

  private static String executableName(String token) {
    int slash = token.lastIndexOf('/');
    return slash >= 0 ? token.substring(slash + 1) : token;
  }

  /** HTTP 读（GET 类）：默认放行，只挡内网/回环/云元数据等 SSRF 目标。无主机的伪目标（如 web_search）放行。 */
  private void checkHttpRead(String url) {
    String host = hostOf(url);
    if (host == null) {
      return;
    }
    assertNotInternalHost(host);
  }

  /**
   * HTTP 写（POST/PUT/…）：过域名白名单——防止把数据外发到任意端点。白名单本身即"运营者批准的目标"，故不再叠加 SSRF 解析（内网 POST
   * 需运营者显式白名单，属其决定）；SSRF 兜底集中在默认放行的 READ 路径。
   */
  private void checkHttpWrite(String url) {
    String host = hostOf(url);
    if (host == null) {
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

  private static String hostOf(String url) {
    try {
      return URI.create(url).getHost();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** SSRF 兜底：拒绝主机解析到回环/任意本地/链路本地(含云元数据 169.254.169.254)/站点内网/组播/CGNAT，及 localhost、*.internal。 */
  private static void assertNotInternalHost(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    if (INTERNAL_HOSTS.contains(h) || h.endsWith(INTERNAL_DOMAIN_SUFFIX)) {
      throw new SandboxViolationException("拒绝访问内网 / 元数据主机（SSRF 防护）: " + host + "。这是安全策略，请勿重试。");
    }
    // IPv6 字面量 getHost() 带方括号（如 [fd00::1]），解析前剥掉，ULA/回环等判断才生效
    String lookup = h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : host;
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(lookup);
    } catch (UnknownHostException e) {
      throw new SandboxViolationException("无法解析主机: " + host + "。请检查地址是否正确，勿反复重试。");
    }
    for (InetAddress addr : addresses) {
      if (addr.isLoopbackAddress()
          || addr.isAnyLocalAddress()
          || addr.isLinkLocalAddress()
          || addr.isSiteLocalAddress()
          || addr.isMulticastAddress()
          || isCarrierGradeNat(addr)
          || isIpv6UniqueLocal(addr)) {
        throw new SandboxViolationException(
            "拒绝访问内网 / 保留地址（SSRF 防护）: " + host + " → " + addr.getHostAddress() + "。这是安全策略，请勿重试。");
      }
    }
  }

  /** 100.64.0.0/10（运营商级 NAT，isSiteLocalAddress 不覆盖，单独判）。 */
  private static boolean isCarrierGradeNat(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
  }

  /** IPv6 ULA fc00::/7（唯一本地地址，isSiteLocalAddress 对 IPv6 不覆盖，单独判——否则 [fd00::1] 可绕过）。 */
  private static boolean isIpv6UniqueLocal(InetAddress addr) {
    byte[] b = addr.getAddress();
    return b.length == 16 && (b[0] & 0xFE) == 0xFC;
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
    return List.copyOf(allowedDomainPatterns);
  }

  @Override
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "CRLF_INJECTION_LOGS",
      justification = "entry 经 sanitize() 消去 CR/LF 后才进日志；taint 分析不跨方法追踪该消毒，故局部抑制")
  public boolean add(Category category, String value) {
    String entry = requireNonBlank(value);
    boolean changed;
    String canonical; // 入内存的规范形，也是落库/展示/删除对齐的值（FILE 为归一后的绝对路径）
    if (category == Category.FILE) {
      Path root = normalizeRoot(entry);
      canonical = root.toString();
      changed = allowedRoots.addIfAbsent(root);
    } else if (category == Category.SHELL) {
      canonical = entry;
      changed = allowedCommands.add(entry);
    } else {
      canonical = entry;
      changed = allowedDomainPatterns.addIfAbsent(entry);
    }
    // 写穿：只有内存确有变更才落库（幂等，避免重复写；启动播种重复调用不会重复插入）
    if (changed && store != null) {
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
      canonical = root.toString();
      changed = allowedRoots.remove(root);
    } else if (category == Category.SHELL) {
      canonical = entry;
      changed = allowedCommands.remove(entry);
    } else {
      canonical = entry;
      changed = allowedDomainPatterns.remove(entry);
    }
    if (changed && store != null) {
      store.remove(category, canonical);
    }
    LOG.info("Sandbox 白名单删除 {} -> {}（changed={}）", category, sanitize(entry), changed);
    return changed;
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
