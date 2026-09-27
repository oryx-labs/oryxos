package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.oryxos.core.ToolResult;
import io.oryxos.tool.ToolRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the real MCP SDK transports and JSON serialization against local protocol fixtures. */
class McpTransportCompatibilityTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String FIXTURE_AUTHORIZATION = "Bearer fixture-token";

  @TempDir Path dir;

  @Test
  @Timeout(15)
  void stdioInitializesListsAndCallsThroughRealTransport() throws Exception {
    Path server = writeStdioServer();
    McpClientService service = serviceFor("stdio", server.toString(), null);
    ToolRegistry registry = new ToolRegistry();

    service.connectAll(registry);

    assertRoundTrip(registry, "stdio");
    service.disconnect("fixture", registry);
  }

  @Test
  @Timeout(15)
  void httpSseInitializesListsAndCallsThroughRealTransport() throws Exception {
    try (SseFixture server = new SseFixture()) {
      McpClientService service = serviceFor("http", null, server.baseUrl());
      ToolRegistry registry = new ToolRegistry();

      service.connectAll(registry);

      assertRoundTrip(registry, "http-sse");
      service.disconnect("fixture", registry);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"http", "sse"})
  @Timeout(30)
  void sseUsesConfiguredEndpointPathAndQuery(String transport) throws Exception {
    try (SseFixture server = new SseFixture()) {
      McpClientService service =
          serviceFor(transport, null, server.baseUrl() + "/tenant/events?tenant=x%2Fy");
      ToolRegistry registry = new ToolRegistry();
      try {
        service.connectAll(registry);
        assertRoundTrip(registry, transport);
        assertEquals(List.of("/tenant/events?tenant=x%2Fy"), server.eventRequests);
        assertTrue(server.requestHeaders.stream().allMatch(FIXTURE_AUTHORIZATION::equals));
      } finally {
        service.disconnect("fixture", registry);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/tenant/rpc?tenant=x%2Fy", "/tenant%20space/rpc/?tenant=one&mode=a%2Fb"})
  @Timeout(30)
  void streamableUsesConfiguredEndpointPathAndQuery(String endpoint) throws Exception {
    try (StreamableFixture server = new StreamableFixture()) {
      McpClientService service = serviceFor("streamable", null, server.baseUrl() + endpoint);
      ToolRegistry registry = new ToolRegistry();
      try {
        service.connectAll(registry);
        assertRoundTrip(registry, "streamable");
        assertTrue(server.postRequests.size() >= 3);
        assertTrue(
            server.postRequests.stream().allMatch(endpoint::equals),
            server.postRequests.toString());
        assertTrue(server.requestHeaders.stream().allMatch(FIXTURE_AUTHORIZATION::equals));
      } finally {
        service.disconnect("fixture", registry);
      }
    }
  }

  @Test
  @Timeout(30)
  void streamableRootAddressKeepsDefaultEndpoint() throws Exception {
    try (StreamableFixture server = new StreamableFixture()) {
      McpClientService service = serviceFor("streamable", null, server.baseUrl() + "/");
      ToolRegistry registry = new ToolRegistry();
      try {
        service.connectAll(registry);
        assertRoundTrip(registry, "streamable");
        assertTrue(server.postRequests.stream().allMatch(uri -> "/mcp".equals(uri)));
      } finally {
        service.disconnect("fixture", registry);
      }
    }
  }

  @Test
  @Timeout(30)
  void customSseEndpointAcceptsSameOriginAbsoluteMessageEndpoint() throws Exception {
    try (SseFixture server = new SseFixture(true)) {
      McpClientService service = serviceFor("sse", null, server.baseUrl() + "/tenant/events");
      ToolRegistry registry = new ToolRegistry();
      try {
        service.connectAll(registry);
        assertRoundTrip(registry, "absolute-messages");
        assertEquals(List.of("/tenant/events"), server.eventRequests);
      } finally {
        service.disconnect("fixture", registry);
      }
    }
  }

  private McpClientService serviceFor(String transport, String command, String url)
      throws IOException {
    Path config = dir.resolve(transport + ".yaml");
    Files.writeString(
        config,
        "servers:\n"
            + "  - name: fixture\n"
            + "    transport: "
            + transport
            + "\n"
            + (command == null ? "" : "    command: " + command + "\n")
            + (url == null ? "" : "    url: " + url + "\n")
            + "    request_timeout: 5\n"
            + "    headers:\n      Authorization: "
            + FIXTURE_AUTHORIZATION
            + "\n");
    return new McpClientService(new McpConfigLoader(config));
  }

  private static void assertRoundTrip(ToolRegistry registry, String transport) {
    assertTrue(registry.contains("echo"));
    ToolResult result =
        registry
            .get("echo")
            .orElseThrow()
            .execute(MAPPER.createObjectNode().put("value", transport));
    assertTrue(result.success());
    assertEquals("reply:" + transport, result.content());
  }

  private Path writeStdioServer() throws IOException {
    Path script = dir.resolve("mcp-fixture.py");
    Files.writeString(
        script,
        """
        #!/usr/bin/env python3
        import json, sys

        for line in sys.stdin:
            request = json.loads(line)
            request_id = request.get("id")
            if request_id is None:
                continue
            method = request.get("method")
            if method == "initialize":
                result = {"protocolVersion": "2024-11-05", "capabilities": {"tools": {}},
                          "serverInfo": {"name": "stdio-fixture", "version": "1"}}
            elif method == "tools/list":
                result = {"tools": [{"name": "echo", "description": "echo",
                                      "inputSchema": {"type": "object"}}]}
            elif method == "tools/call":
                value = request["params"]["arguments"]["value"]
                result = {"content": [{"type": "text", "text": "reply:" + value}],
                          "isError": False}
            else:
                result = {}
            print(json.dumps({"jsonrpc": "2.0", "id": request_id, "result": result}), flush=True)
        """);
    try {
      Files.setPosixFilePermissions(
          script,
          EnumSet.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE));
    } catch (UnsupportedOperationException e) {
      assertTrue(script.toFile().setExecutable(true), "fixture must be executable");
    }
    return script;
  }

  private static final class SseFixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final CountDownLatch closed = new CountDownLatch(1);
    private final List<String> eventRequests = new CopyOnWriteArrayList<>();
    private final List<String> requestHeaders = new CopyOnWriteArrayList<>();
    private final boolean absoluteMessageEndpoint;
    private volatile OutputStream events;

    private SseFixture() throws IOException {
      this(false);
    }

    private SseFixture(boolean absoluteMessageEndpoint) throws IOException {
      this.absoluteMessageEndpoint = absoluteMessageEndpoint;
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/sse", this::openEvents);
      server.createContext("/tenant/events", this::openEvents);
      server.createContext("/messages", this::receiveMessage);
      server.setExecutor(executor);
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void openEvents(HttpExchange exchange) throws IOException {
      eventRequests.add(exchange.getRequestURI().toString());
      requestHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, 0);
      try (OutputStream output = exchange.getResponseBody()) {
        events = output;
        sendEvent("endpoint", (absoluteMessageEndpoint ? baseUrl() : "") + "/messages");
        try {
          closed.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      } finally {
        exchange.close();
      }
    }

    private void receiveMessage(HttpExchange exchange) throws IOException {
      requestHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
      JsonNode request = MAPPER.readTree(exchange.getRequestBody());
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      if (!request.has("id")) {
        return;
      }
      String method = request.path("method").asText();
      JsonNode result;
      if ("initialize".equals(method)) {
        result =
            MAPPER.readTree(
                """
                {"protocolVersion":"2024-11-05","capabilities":{"tools":{}},
                 "serverInfo":{"name":"sse-fixture","version":"1"}}
                """);
      } else if ("tools/list".equals(method)) {
        result =
            MAPPER.readTree(
                """
                {"tools":[{"name":"echo","description":"echo",
                            "inputSchema":{"type":"object"}}]}
                """);
      } else if ("tools/call".equals(method)) {
        String value = request.path("params").path("arguments").path("value").asText();
        result =
            MAPPER
                .createObjectNode()
                .set(
                    "content",
                    MAPPER
                        .createArrayNode()
                        .add(
                            MAPPER
                                .createObjectNode()
                                .put("type", "text")
                                .put("text", "reply:" + value)));
      } else {
        result = MAPPER.createObjectNode();
      }
      var response = MAPPER.createObjectNode();
      response.put("jsonrpc", "2.0");
      response.set("id", request.get("id"));
      response.set("result", result);
      sendEvent("message", MAPPER.writeValueAsString(response));
    }

    private synchronized void sendEvent(String type, String data) throws IOException {
      OutputStream output = events;
      if (output == null) {
        throw new IOException("SSE stream is not connected");
      }
      output.write(
          ("event: " + type + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
      output.flush();
    }

    @Override
    public void close() throws Exception {
      closed.countDown();
      server.stop(0);
      executor.shutdownNow();
      assertTrue(
          executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
    }
  }

  private static final class StreamableFixture implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<String> postRequests = new CopyOnWriteArrayList<>();
    private final List<String> requestHeaders = new CopyOnWriteArrayList<>();

    private StreamableFixture() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::receive);
      server.setExecutor(executor);
      server.start();
    }

    private String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void receive(HttpExchange exchange) throws IOException {
      try {
        if (!"POST".equals(exchange.getRequestMethod())) {
          exchange.sendResponseHeaders(405, -1);
          return;
        }
        postRequests.add(exchange.getRequestURI().toString());
        requestHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        if (!request.has("id")) {
          exchange.sendResponseHeaders(202, -1);
          return;
        }
        JsonNode result;
        String method = request.path("method").asText();
        if ("initialize".equals(method)) {
          result =
              MAPPER.readTree(
                  """
                  {"protocolVersion":"2025-11-25","capabilities":{"tools":{}},
                   "serverInfo":{"name":"streamable-fixture","version":"1"}}
                  """);
        } else if ("tools/list".equals(method)) {
          result =
              MAPPER.readTree(
                  """
                  {"tools":[{"name":"echo","description":"echo",
                              "inputSchema":{"type":"object"}}]}
                  """);
        } else if ("tools/call".equals(method)) {
          result =
              MAPPER
                  .createObjectNode()
                  .set(
                      "content",
                      MAPPER
                          .createArrayNode()
                          .add(
                              MAPPER
                                  .createObjectNode()
                                  .put("type", "text")
                                  .put(
                                      "text",
                                      "reply:"
                                          + request
                                              .path("params")
                                              .path("arguments")
                                              .path("value")
                                              .asText())));
        } else {
          result = MAPPER.createObjectNode();
        }
        var response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        byte[] body = MAPPER.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
      } finally {
        exchange.close();
      }
    }

    @Override
    public void close() throws Exception {
      server.stop(0);
      executor.shutdownNow();
      assertTrue(
          executor.awaitTermination(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
    }
  }
}
