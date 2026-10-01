package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.tool.ToolRegistry;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server 的连接维护与工具注册：启动时连接全部配置的 server，tools/list 逐个包装成 OryxTool 注册进 ToolRegistry（ReAct
 * 循环由此对来源无感知）。管理台 CRUD（31 节）新增/删除一个 server 也走同一段 {@link #connect}/{@link #disconnect}，不需要重启。
 *
 * <p>失联的 server 只 WARN 跳过——外部依赖的可用性不是自己的可用性，不能变成自己的启动故障。 连接工厂构造可注入（测试替身），生产默认按 transport 分派：{@code
 * stdio} 起本地子进程，{@code http}/{@code sse} 连远程 SSE，{@code streamable} 连 Streamable HTTP，并透传配置的请求头。其余
 * transport 一律跳过。{@code auto} 优先 Streamable，仅初始化阶段的明确 legacy 响应允许同端点 SSE 回退，工具调用不切换或重放。
 */
public class McpClientService {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);

  /** 连接探测在启动和管理 API 写路径同步执行，不能继承最长一小时的业务调用超时，否则故障 server 会阻塞整个控制面。 */
  private static final Duration MAX_CONNECT_PROBE_TIMEOUT = Duration.ofSeconds(60);

  /**
   * How long a status probe waits before a server is called unreachable. Short, because the admin
   * list polls every server and a hung one must not hold the response.
   */
  private static final Duration STATUS_PROBE_TIMEOUT = Duration.ofSeconds(2);

  /**
   * Polls inside this window reuse the last successful probe, so a healthy server is pinged at most
   * once per window. A failed probe drops the entry, so a dead one is paid for once.
   */
  private static final Duration DEFAULT_STATUS_PROBE_CACHE = Duration.ofSeconds(15);

  private static final Set<String> SUPPORTED_TRANSPORTS =
      Set.of(
          McpServerConfig.TRANSPORT_STDIO,
          McpServerConfig.TRANSPORT_HTTP,
          McpServerConfig.TRANSPORT_SSE,
          McpServerConfig.TRANSPORT_STREAMABLE,
          McpServerConfig.TRANSPORT_AUTO);

  private final McpConfigLoader configLoader;
  private final Function<McpServerConfig, McpSyncClient> clientFactory;

  // 运行时状态（供管理台状态查询 + disconnect 用）：server 名 -> 已连接的客户端 / 它注册过的工具名 / 上次失败原因。
  // 写路径（connect/disconnect）经 McpServerAdminService 串行，但读路径 status() 无锁裸读——必须用并发 Map，
  // 否则管理台查询与增删并发时 HashMap 结构修改中的 get 是未定义行为。
  private final Map<String, McpSyncClient> activeClients = new ConcurrentHashMap<>();
  private final Map<String, List<String>> registeredTools = new ConcurrentHashMap<>();
  private final Map<String, String> lastErrors = new ConcurrentHashMap<>();
  // Set by connectAll so the shutdown path can unregister the tools it added; a destroy method
  // takes no arguments, and leaving them registered would outlive their transport.
  private volatile ToolRegistry ownRegistry;

  private final Duration statusProbeCache;

  /** Last successful status probe per server, on {@link System#nanoTime()}. */
  private final Map<String, Long> probeTimestamps = new ConcurrentHashMap<>();

  public McpClientService(McpConfigLoader configLoader) {
    this(configLoader, McpClientService::connectDefault);
  }

  public McpClientService(
      McpConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory) {
    this(configLoader, clientFactory, DEFAULT_STATUS_PROBE_CACHE);
  }

  /** {@code statusProbeCache} of zero makes every status call probe, which tests need. */
  McpClientService(
      McpConfigLoader configLoader,
      Function<McpServerConfig, McpSyncClient> clientFactory,
      Duration statusProbeCache) {
    this.configLoader = configLoader;
    this.clientFactory = clientFactory;
    this.statusProbeCache = statusProbeCache;
  }

  /** 启动时的全量连接：加载配置逐个 {@link #connect}，单个失败只 WARN 不拖垮其余。 */
  public void connectAll(ToolRegistry registry) {
    ownRegistry = registry;
    for (McpServerConfig config : configLoader.load()) {
      connect(config, registry);
    }
  }

  /**
   * 连接单个 server 并把它的工具注册进 registry；管理台增/改一个 server 时调用（不需要重启即可生效）。 transport 不受支持、连接失败都只记 WARN + 落
   * {@link #lastErrors}，不抛异常——外部依赖的可用性不是自己的可用性。
   */
  public void connect(McpServerConfig config, ToolRegistry registry) {
    if (!SUPPORTED_TRANSPORTS.contains(config.transport())) {
      String msg = "transport " + config.transport() + " 核心阶段不支持";
      LOG.warn("MCP server {} 的 {}，跳过", s(config.name()), s(msg));
      lastErrors.put(config.name(), msg);
      return;
    }
    McpSyncClient client = null;
    List<String> toolNames = new ArrayList<>();
    try {
      client = clientFactory.apply(config);
      try {
        client.initialize();
      } catch (RuntimeException e) {
        if (!McpServerConfig.TRANSPORT_AUTO.equals(config.transport()) || !isLegacyEndpoint(e)) {
          throw e;
        }
        closeQuietly(config.name(), client);
        client = null;
        LOG.info("MCP server {} 初次连接回退到 legacy SSE", s(config.name()));
        // 与 Streamable 探测使用同一端点；仅配置根地址时也不猜测另一个 /sse 路径。
        client = connectHttpSse(config, "/mcp");
        client.initialize();
      }
      for (var tool : listToolsForConnect(client, config).tools()) {
        registry.registerMcpTool(config.name(), new McpToolAdapter(client, tool));
        toolNames.add(tool.name());
      }
      activeClients.put(config.name(), client);
      registeredTools.put(config.name(), List.copyOf(toolNames));
      lastErrors.remove(config.name());
    } catch (RuntimeException e) {
      // 外部依赖失联不拖垮自身启动——只 WARN，OryxOS 照常起（课件守点）。
      // listTools 中途失败（重名、协议错）时：已注册的工具必须卸掉，客户端必须关掉，否则
      // 管理台显示未连接，Agent 仍能调到半截 MCP 工具，stdio 子进程也会泄漏。
      for (String toolName : toolNames) {
        registry.unregister(toolName);
      }
      closeQuietly(config.name(), client);
      LOG.warn("MCP server {} 连接失败，跳过它的工具: {}", s(config.name()), s(e.getMessage()));
      // ConcurrentHashMap 不收 null：异常无 message 时落异常类名
      lastErrors.put(
          config.name(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /** 断开一个 server：注销它注册过的工具、清空运行时状态。管理台删除/改配置一个 server 时调用。 */
  public void disconnect(String serverName, ToolRegistry registry) {
    if (registry != null) {
      for (String toolName : registeredTools.getOrDefault(serverName, List.of())) {
        registry.unregister(toolName);
      }
    }
    registeredTools.remove(serverName);
    probeTimestamps.remove(serverName);
    closeQuietly(serverName, activeClients.remove(serverName));
    lastErrors.remove(serverName);
  }

  /**
   * Close every open connection. Registered as the bean's destroy method: connections are opened
   * for any command whose context builds a tool registry, and nothing else releases them — a stdio
   * server only exits when its client closes the transport.
   */
  public void closeAll() {
    ToolRegistry registry = ownRegistry;
    for (String serverName : List.copyOf(activeClients.keySet())) {
      if (registry != null) {
        for (String toolName : registeredTools.getOrDefault(serverName, List.of())) {
          registry.unregister(toolName);
        }
      }
      closeQuietly(serverName, activeClients.remove(serverName));
    }
    registeredTools.clear();
    lastErrors.clear();
    probeTimestamps.clear();
    ownRegistry = null;
  }

  private static void closeQuietly(String serverName, McpSyncClient client) {
    if (client == null) {
      return;
    }
    try {
      client.closeGracefully();
    } catch (RuntimeException e) {
      LOG.warn("MCP server {} 断开连接时出错（忽略）: {}", s(serverName), s(e.getMessage()));
    }
  }

  /** 单个 server 的运行时状态：是否连上、给了哪些工具、失败原因。 */
  public McpServerStatus status(String serverName) {
    McpSyncClient client = activeClients.get(serverName);
    if (client == null) {
      return new McpServerStatus(serverName, false, lastErrors.get(serverName), List.of());
    }
    if (!isReachable(serverName, client)) {
      // The transport is gone but nothing closed it, so the entry would otherwise keep reporting a
      // usable server and leave its tools registered. Drop it; the next connect can rebuild.
      String error = lastErrors.get(serverName);
      disconnect(serverName, ownRegistry);
      lastErrors.put(serverName, error);
      return new McpServerStatus(serverName, false, error, List.of());
    }
    return new McpServerStatus(
        serverName, true, null, registeredTools.getOrDefault(serverName, List.of()));
  }

  /**
   * Whether {@code client} answers, cached for the configured window. A server that dies between
   * calls reported {@code connected} forever; the probe bounds the wait so a hung server costs one
   * timeout per window rather than one per poll.
   */
  private boolean isReachable(String serverName, McpSyncClient client) {
    long now = System.nanoTime();
    Long last = probeTimestamps.get(serverName);
    if (last != null && now - last < statusProbeCache.toNanos()) {
      return true;
    }
    FutureTask<Boolean> probe =
        new FutureTask<>(
            () -> {
              client.ping();
              return Boolean.TRUE;
            });
    Thread.ofVirtual().name("oryxos-mcp-status-probe").start(probe);
    try {
      probe.get(STATUS_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      probeTimestamps.put(serverName, now);
      lastErrors.remove(serverName);
      return true;
    } catch (TimeoutException e) {
      probe.cancel(true);
      lastErrors.put(
          serverName,
          "MCP server " + serverName + " 无响应：探测超过 " + STATUS_PROBE_TIMEOUT.toSeconds() + " 秒");
      return false;
    } catch (InterruptedException e) {
      probe.cancel(true);
      Thread.currentThread().interrupt();
      lastErrors.put(serverName, "MCP server " + serverName + " 探测被中断");
      return false;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      lastErrors.put(
          serverName,
          "MCP server "
              + serverName
              + " 已断开: "
              + (cause == null ? e.toString() : cause.toString()));
      return false;
    }
  }

  static Duration connectProbeTimeout(McpServerConfig config) {
    Duration configured = config.requestTimeout();
    return configured.compareTo(MAX_CONNECT_PROBE_TIMEOUT) > 0
        ? MAX_CONNECT_PROBE_TIMEOUT
        : configured;
  }

  private static McpSchema.ListToolsResult listToolsForConnect(
      McpSyncClient client, McpServerConfig config) {
    Duration timeout = connectProbeTimeout(config);
    FutureTask<McpSchema.ListToolsResult> probe = new FutureTask<>(client::listTools);
    Thread.ofVirtual().name("oryxos-mcp-tools-list-probe").start(probe);
    try {
      return probe.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      probe.cancel(true);
      throw new IllegalStateException(
          "MCP server " + config.name() + " tools/list 探测超过 " + timeout.toSeconds() + " 秒", e);
    } catch (InterruptedException e) {
      probe.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("MCP server " + config.name() + " tools/list 探测被中断", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("MCP server " + config.name() + " tools/list 探测失败", cause);
    }
  }

  private static McpSyncClient connectDefault(McpServerConfig config) {
    if (McpServerConfig.isStreamable(config.transport())
        || McpServerConfig.TRANSPORT_AUTO.equals(config.transport())) {
      return connectStreamable(config);
    }
    if (McpServerConfig.isHttpSse(config.transport())) {
      return connectHttpSse(config);
    }
    return connectStdio(config);
  }

  private static McpSyncClient connectStdio(McpServerConfig config) {
    String[] parts = config.command().trim().split("\\s+");
    ServerParameters params =
        ServerParameters.builder(parts[0])
            .args(java.util.Arrays.copyOfRange(parts, 1, parts.length))
            .env(config.env())
            .build();
    return McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
        .requestTimeout(config.requestTimeout())
        .build();
  }

  /** Remote SSE ({@code http}/{@code sse}): SDK SSE client to {@code url}, with headers. */
  private static McpSyncClient connectHttpSse(McpServerConfig config) {
    return connectHttpSse(config, "/sse");
  }

  private static McpSyncClient connectHttpSse(McpServerConfig config, String defaultPath) {
    HttpEndpoint endpoint = httpEndpoint(config.url(), defaultPath);
    HttpClientSseClientTransport.Builder transport =
        HttpClientSseClientTransport.builder(endpoint.baseUrl()).sseEndpoint(endpoint.requestUrl());
    if (!config.headers().isEmpty()) {
      transport.httpRequestCustomizer(
          (request, method, uri, body, context) -> config.headers().forEach(request::header));
    }
    return McpClient.sync(transport.build()).requestTimeout(config.requestTimeout()).build();
  }

  /** Remote Streamable HTTP ({@code streamable}): SDK Streamable client to {@code url}. */
  private static McpSyncClient connectStreamable(McpServerConfig config) {
    HttpEndpoint endpoint = httpEndpoint(config.url(), "/mcp");
    HttpClientStreamableHttpTransport.Builder transport =
        HttpClientStreamableHttpTransport.builder(endpoint.baseUrl())
            .endpoint(endpoint.requestUrl());
    if (McpServerConfig.TRANSPORT_AUTO.equals(config.transport())) {
      transport.clientBuilder(McpAutoHttpClient.builder());
    }
    if (!config.headers().isEmpty()) {
      transport.httpRequestCustomizer(
          (request, method, uri, body, context) -> config.headers().forEach(request::header));
    }
    return McpClient.sync(transport.build()).requestTimeout(config.requestTimeout()).build();
  }

  /** SDK 的 base URI 和 endpoint 分开传入；完整端点保留 raw path/query，根地址沿用旧默认路径。 */
  private static HttpEndpoint httpEndpoint(String url, String defaultPath) {
    URI uri = URI.create(url);
    String path = uri.getRawPath();
    if (path == null || path.isEmpty() || "/".equals(path)) {
      path = defaultPath;
    }
    if (uri.getRawQuery() != null) {
      path += "?" + uri.getRawQuery();
    }
    String baseUrl = uri.resolve("/").toString();
    // 传入绝对端点 URL，让 SDK 按同源校验后直接使用，避免再次解释相对路径。
    return new HttpEndpoint(baseUrl, baseUrl.substring(0, baseUrl.length() - 1) + path);
  }

  private record HttpEndpoint(String baseUrl, String requestUrl) {}

  private static boolean isLegacyEndpoint(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof McpAutoHttpClient.LegacyEndpointException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  private static String s(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
