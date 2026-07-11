package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.oryxos.tool.ToolRegistry;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP server 的连接维护与工具注册：启动时连接全部配置的 server，tools/list 逐个包装成 OryxTool 注册进 ToolRegistry（ReAct
 * 循环由此对来源无感知）。
 *
 * <p>失联的 server 只 WARN 跳过——外部依赖的可用性不是自己的可用性，不能变成自己的启动故障。 连接工厂构造可注入（测试替身），生产默认走 stdio transport。
 */
public class McpClientService {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientService.class);

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final McpConfigLoader configLoader;
  private final Function<McpServerConfig, McpSyncClient> clientFactory;

  public McpClientService(McpConfigLoader configLoader) {
    this(configLoader, McpClientService::connectStdio);
  }

  public McpClientService(
      McpConfigLoader configLoader, Function<McpServerConfig, McpSyncClient> clientFactory) {
    this.configLoader = configLoader;
    this.clientFactory = clientFactory;
  }

  public void connectAll(ToolRegistry registry) {
    List<McpServerConfig> configs = configLoader.load();
    for (McpServerConfig config : configs) {
      if (!"stdio".equals(config.transport())) {
        LOG.warn(
            "MCP server {} 的 transport {} 核心阶段不支持，跳过", s(config.name()), s(config.transport()));
        continue;
      }
      try {
        McpSyncClient client = clientFactory.apply(config);
        client.initialize();
        client
            .listTools()
            .tools()
            .forEach(tool -> registry.register(new McpToolAdapter(client, tool)));
      } catch (RuntimeException e) {
        // 外部依赖失联不拖垮自身启动——只 WARN，OryxOS 照常起（课件守点）
        LOG.warn("MCP server {} 连接失败，跳过它的工具: {}", s(config.name()), s(e.getMessage()));
      }
    }
  }

  private static McpSyncClient connectStdio(McpServerConfig config) {
    String[] parts = config.command().trim().split("\\s+");
    ServerParameters params =
        ServerParameters.builder(parts[0])
            .args(java.util.Arrays.copyOfRange(parts, 1, parts.length))
            .env(config.env())
            .build();
    return McpClient.sync(new StdioClientTransport(params)).requestTimeout(REQUEST_TIMEOUT).build();
  }

  private static String s(String value) {
    return value == null ? "" : value.replace('\r', '_').replace('\n', '_');
  }
}
