package io.oryxos.tool.sandbox;

import io.oryxos.core.sandbox.SandboxWhitelist;
import io.oryxos.core.sandbox.SandboxWhitelistStore;
import java.net.URI;
import java.nio.file.Path;
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
      case HTTP_REQUEST:
        checkHttpUrl(action.target());
        break;
      default:
        // 安全默认：未来若新增未覆盖的动作类型，deny 而非静默放行（宪法 VI）
        throw new SandboxViolationException("未知的沙箱动作类型: " + action.type());
    }
  }

  private void checkFilePath(String rawPath) {
    Path target = Path.of(rawPath).normalize().toAbsolutePath();
    boolean allowed = allowedRoots.stream().anyMatch(target::startsWith);
    if (!allowed) {
      throw new SandboxViolationException("路径不在白名单内: " + rawPath);
    }
  }

  private void checkShellCommand(String command) {
    String firstToken = command.trim().split("\\s+")[0];
    if (!allowedCommands.contains(firstToken)) {
      throw new SandboxViolationException("命令不在白名单内: " + firstToken);
    }
  }

  private void checkHttpUrl(String url) {
    String host = URI.create(url).getHost();
    boolean allowed =
        host != null
            && allowedDomainPatterns.stream().anyMatch(pattern -> matchesDomain(host, pattern));
    if (!allowed) {
      throw new SandboxViolationException("域名不在白名单内: " + host);
    }
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
