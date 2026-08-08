package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.oryxos.core.mcp.McpServerConfig;
import io.oryxos.core.mcp.McpServerStatus;
import io.oryxos.tool.ToolRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server 的连接维护与工具注册：启动时连接全部配置的 server，tools/list 逐个包装成 OryxTool 注册进 ToolRegistry（ReAct
 * 循环由此对来源无感知）。管理台 CRUD（31 节）新增/删除一个 server 也走同一段 {@link #connect}/{@link #disconnect}，不需要重启。
 *
 * <p>失联的 server 只 WARN 跳过——外部依赖的可用性不是自己的可用性，不能变成自己的启动故障。 连接工厂构造可注入（测试替身），生产默认按 transport 分派：{@code
 * stdio} 起本地子进程，{@code http} 连远程 SSE server，并透传配置的请求头。其余 transport 一律跳过。
 */
public class McpClientService {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final Set<String> SUPPORTED_TRANSPORTS =
      Set.of(McpServerConfig.TRANSPORT_STDIO, McpServerConfig.TRANSPORT_HTTP);

  private final McpConfigLoader configLoader;
  private final Function<McpServerConfig, McpSyncClient> clientFactory;

  // 运行时状态（供管理台状态查询 + disconnect 用）：server 名 -> 已连接的客户端 / 它注册过的工具名 / 上次失败原因。
  private final Map<String, McpSyncClient> activeClients = new LinkedHashMap<>();
  private final Map<String, List<String>> registeredTools = new LinkedHashMap<>();
  private final Map<String, String> lastErrors = new LinkedHashMap<>();

  public McpClientService(McpConfigLoader configLoader) {
    this(configLoader, McpClientService::connectDefault);
  }

  public McpClientService(
      McpConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory) {
    this.configLoader = configLoader;
    this.clientFactory = clientFactory;
  }

  /** 启动时的全量连接：加载配置逐个 {@link #connect}，单个失败只 WARN 不拖垮其余。 */
  public void connectAll(ToolRegistry registry) {
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
    try {
      McpSyncClient client = clientFactory.apply(config);
      client.initialize();
      List<String> toolNames = new ArrayList<>();
      client
          .listTools()
          .tools()
          .forEach(
              tool -> {
                registry.registerMcpTool(config.name(), new McpToolAdapter(client, tool));
                toolNames.add(tool.name());
              });
      activeClients.put(config.name(), client);
      registeredTools.put(config.name(), toolNames);
      lastErrors.remove(config.name());
    } catch (RuntimeException e) {
      // 外部依赖失联不拖垮自身启动——只 WARN，OryxOS 照常起（课件守点）
      LOG.warn("MCP server {} 连接失败，跳过它的工具: {}", s(config.name()), s(e.getMessage()));
      lastErrors.put(config.name(), e.getMessage());
    }
  }

  /** 断开一个 server：注销它注册过的工具、清空运行时状态。管理台删除/改配置一个 server 时调用。 */
  public void disconnect(String serverName, ToolRegistry registry) {
    for (String toolName : registeredTools.getOrDefault(serverName, List.of())) {
      registry.unregister(toolName);
    }
    registeredTools.remove(serverName);
    McpSyncClient client = activeClients.remove(serverName);
    if (client != null) {
      try {
        client.closeGracefully();
      } catch (RuntimeException e) {
        LOG.warn("MCP server {} 断开连接时出错（忽略）: {}", s(serverName), s(e.getMessage()));
      }
    }
    lastErrors.remove(serverName);
  }

  /** 单个 server 的运行时状态：是否连上、给了哪些工具、失败原因。 */
  public McpServerStatus status(String serverName) {
    if (activeClients.containsKey(serverName)) {
      return new McpServerStatus(
          serverName, true, null, registeredTools.getOrDefault(serverName, List.of()));
    }
    return new McpServerStatus(serverName, false, lastErrors.get(serverName), List.of());
  }

  private static McpSyncClient connectDefault(McpServerConfig config) {
    return McpServerConfig.TRANSPORT_HTTP.equals(config.transport())
        ? connectHttp(config)
        : connectStdio(config);
  }

  private static McpSyncClient connectStdio(McpServerConfig config) {
    String[] parts = config.command().trim().split("\\s+");
    ServerParameters params =
        ServerParameters.builder(parts[0])
            .args(java.util.Arrays.copyOfRange(parts, 1, parts.length))
            .env(config.env())
            .build();
    return McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper()))
        .requestTimeout(REQUEST_TIMEOUT)
        .build();
  }

  /** 远程 http server：用 SDK 自带的 SSE 客户端连接 {@code url}，并把配置请求头应用到每次请求。 */
  private static McpSyncClient connectHttp(McpServerConfig config) {
    HttpClientSseClientTransport.Builder transport =
        HttpClientSseClientTransport.builder(config.url());
    if (!config.headers().isEmpty()) {
      transport.httpRequestCustomizer(
          (request, method, uri, body, context) -> config.headers().forEach(request::header));
    }
    return McpClient.sync(transport.build()).requestTimeout(REQUEST_TIMEOUT).build();
  }

  private static String s(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
