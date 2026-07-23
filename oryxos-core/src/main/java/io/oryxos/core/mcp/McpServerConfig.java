package io.oryxos.core.mcp;

import java.util.Map;

/**
 * 一个外部 MCP server 的连接配置（{@code .oryxos/mcp_servers.yaml} 条目）。定义在 core：Web 层按依赖倒置只认这个契约，具体连接
 * 实现（stdio 子进程 / 远程 http）留给 oryxos-tool。
 *
 * <p>{@code transport} 目前支持 {@code stdio}（本地子进程，用 {@code command}/{@code env}）与 {@code http}（远程
 * server，用 {@code url}/{@code headers}）；其余值一律跳过并 WARN（未知传输不拖垮启动）。{@code headers} 同 {@code env} 支持
 * {@code ${ENV}} 占位——鉴权 token 走环境变量，不明文落盘（宪法：敏感配置走环境变量）。
 */
public record McpServerConfig(
    String name,
    String transport,
    String command,
    Map<String, String> env,
    String url,
    Map<String, String> headers) {

  public McpServerConfig {
    env = env == null ? Map.of() : Map.copyOf(env);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  public static final String TRANSPORT_STDIO = "stdio";
  public static final String TRANSPORT_HTTP = "http";
}
