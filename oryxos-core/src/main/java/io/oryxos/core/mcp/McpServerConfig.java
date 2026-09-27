package io.oryxos.core.mcp;

import java.time.Duration;
import java.util.Map;

/**
 * 一个外部 MCP server 的连接配置（{@code .oryxos/mcp_servers.yaml} 条目）。定义在 core：Web 层按依赖倒置只认这个契约，具体连接
 * 实现（stdio 子进程 / 远程 http）留给 oryxos-tool。
 *
 * <p>{@code transport} 目前支持 {@code stdio}（本地子进程，用 {@code command}/{@code env}）、{@code http}/{@code
 * sse} （远程 SSE，用 {@code url}/{@code headers}）与 {@code streamable}（Streamable HTTP，同样用 {@code
 * url}/{@code headers}），{@code auto} 仅在初次连接时选择这两种远程传输；其余值一律跳过并 WARN（未知传输不拖垮启动）。{@code headers} 同
 * {@code env} 支持 {@code ${ENV}} 占位——鉴权 token 走环境变量，不明文落盘（宪法：敏感配置走环境变量）。{@code
 * requestTimeoutSeconds} 为单 server 请求超时，缺省 30 秒。
 */
public record McpServerConfig(
    String name,
    String transport,
    String command,
    Map<String, String> env,
    String url,
    Map<String, String> headers,
    int requestTimeoutSeconds) {

  /** 沿用 OryxOS 在引入可配置项前写死的请求超时，非 MCP SDK 默认值。 */
  public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 30;

  public static final int MIN_REQUEST_TIMEOUT_SECONDS = 1;
  public static final int MAX_REQUEST_TIMEOUT_SECONDS = 3600;

  public McpServerConfig {
    env = env == null ? Map.of() : Map.copyOf(env);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    if (requestTimeoutSeconds < MIN_REQUEST_TIMEOUT_SECONDS
        || requestTimeoutSeconds > MAX_REQUEST_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException(
          "MCP 请求超时 request_timeout/requestTimeoutSeconds 必须在 "
              + MIN_REQUEST_TIMEOUT_SECONDS
              + ".."
              + MAX_REQUEST_TIMEOUT_SECONDS
              + " 秒之间: "
              + requestTimeoutSeconds);
    }
  }

  /** 兼容未配置 timeout 的既有调用方与配置，保持原有 30 秒行为。 */
  public McpServerConfig(
      String name,
      String transport,
      String command,
      Map<String, String> env,
      String url,
      Map<String, String> headers) {
    this(name, transport, command, env, url, headers, DEFAULT_REQUEST_TIMEOUT_SECONDS);
  }

  public Duration requestTimeout() {
    return Duration.ofSeconds(requestTimeoutSeconds);
  }

  public static final String TRANSPORT_STDIO = "stdio";

  /** Remote SSE; historical name {@code http}. {@link #TRANSPORT_SSE} is an alias. */
  public static final String TRANSPORT_HTTP = "http";

  /** Remote SSE; alias of {@link #TRANSPORT_HTTP}. */
  public static final String TRANSPORT_SSE = "sse";

  /** Remote Streamable HTTP (current MCP recommended transport). */
  public static final String TRANSPORT_STREAMABLE = "streamable";

  /** 初次连接优先 Streamable HTTP，明确的 legacy 响应才回退到同端点 SSE。 */
  public static final String TRANSPORT_AUTO = "auto";

  public static boolean isHttpSse(String transport) {
    return TRANSPORT_HTTP.equals(transport) || TRANSPORT_SSE.equals(transport);
  }

  public static boolean isStreamable(String transport) {
    return TRANSPORT_STREAMABLE.equals(transport);
  }

  /** HTTP-family transports that require {@code url}. */
  public static boolean isRemoteHttp(String transport) {
    return isHttpSse(transport) || isStreamable(transport) || TRANSPORT_AUTO.equals(transport);
  }
}
