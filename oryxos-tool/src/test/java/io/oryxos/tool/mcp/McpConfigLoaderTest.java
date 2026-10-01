package io.oryxos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.oryxos.core.mcp.McpServerConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpConfigLoaderTest {

  @TempDir Path dir;

  private Path configFile() {
    return dir.resolve("mcp_servers.yaml");
  }

  private void write(String yaml) throws IOException {
    Files.writeString(configFile(), yaml);
  }

  @Test
  @DisplayName("文件缺失时 loadRaw 返回空列表")
  void missingFileReturnsEmpty() {
    assertEquals(0, new McpConfigLoader(configFile()).loadRaw().size());
  }

  @Test
  @DisplayName("YAML 解析失败时 loadRaw 抛 IllegalArgumentException")
  void malformedYamlFailsLoud() throws IOException {
    write("servers:\n  - name: [unclosed\n");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

    assertTrue(ex.getMessage().contains("mcp_servers.yaml 解析失败"));
  }

  @Test
  @DisplayName("顶层 servers 非列表时 loadRaw 抛 IllegalArgumentException")
  void serversNotListFailsLoud() throws IOException {
    write("servers: not-a-list\n");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

    assertTrue(ex.getMessage().contains("servers 必须是列表"));
  }

  @Test
  @DisplayName("servers 条目非对象时 loadRaw 抛 IllegalArgumentException")
  void serversEntryNotMapFailsLoud() throws IOException {
    write("servers:\n  - plain-string\n");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

    assertTrue(ex.getMessage().contains("非对象条目"));
  }

  @Test
  @DisplayName("server 名重复时 loadRaw 抛 IllegalArgumentException")
  void duplicateNameFailsLoud() throws IOException {
    // The name keys the connection everywhere downstream; accepting a duplicate silently replaced
    // the first client without closing it and left its tools registered with no owner.
    write(
        """
        servers:
          - name: dup
            transport: stdio
            command: alpha
          - name: dup
            transport: stdio
            command: beta
        """);
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());
    assertTrue(ex.getMessage().contains("重复"), ex.getMessage());
    assertTrue(ex.getMessage().contains("dup"), ex.getMessage());
  }

  @Test
  @DisplayName("headers/env 非映射时 fail-loud，缺省仍为空 map")
  void envAndHeadersMustBeMaps() throws IOException {
    write(
        """
        servers:
          - name: gh
            transport: sse
            url: https://example.com/mcp
            headers: "Authorization: Bearer secret"
        """);
    IllegalArgumentException headersEx =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());
    assertTrue(headersEx.getMessage().contains("headers"));
    assertTrue(headersEx.getMessage().contains("映射"));

    write(
        """
        servers:
          - name: local
            transport: stdio
            command: echo
            env: []
        """);
    IllegalArgumentException envEx =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());
    assertTrue(envEx.getMessage().contains("env"));
    assertTrue(envEx.getMessage().contains("映射"));

    write(
        """
        servers:
          - name: ok
            transport: stdio
            command: echo
            env: {}
            headers:
              Authorization: "Bearer ${TOKEN}"
        """);
    List<io.oryxos.core.mcp.McpServerConfig> ok = new McpConfigLoader(configFile()).loadRaw();
    assertEquals(1, ok.size());
    assertTrue(ok.get(0).env().isEmpty());
    assertEquals("Bearer ${TOKEN}", ok.get(0).headers().get("Authorization"));
  }

  @Test
  @DisplayName("YAML 1.1 布尔词 yes 不得被 String.valueOf 改成 name=true")
  void rejectsYamlBooleanWordAsName() throws Exception {
    write(
        """
        servers:
          - name: yes
            transport: stdio
            command: echo
        """);
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());
    assertTrue(e.getMessage().contains("字符串") || e.getMessage().contains("Boolean"));

    write(
        """
        servers:
          - name: "yes"
            transport: stdio
            command: echo
        """);
    List<io.oryxos.core.mcp.McpServerConfig> ok = new McpConfigLoader(configFile()).loadRaw();
    assertEquals(1, ok.size());
    assertEquals("yes", ok.get(0).name());
  }

  @Test
  @DisplayName("request_timeout 缺省 30 秒，自定义整数可加载并往返持久化")
  void requestTimeout_defaultsAndRoundTrips() throws Exception {
    write(
        """
        servers:
          - name: default-timeout
            transport: stdio
            command: echo
          - name: long-task
            transport: stdio
            command: echo
            request_timeout: 300
        """);
    McpConfigLoader loader = new McpConfigLoader(configFile());

    List<McpServerConfig> configs = loader.loadRaw();

    assertEquals(
        McpServerConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS, configs.get(0).requestTimeoutSeconds());
    assertEquals(300, configs.get(1).requestTimeoutSeconds());
    assertEquals(Duration.ofSeconds(300), configs.get(1).requestTimeout());

    loader.save(configs);
    String saved = Files.readString(configFile());
    assertTrue(saved.contains("request_timeout: 300"));
    assertEquals(300, loader.loadRaw().get(1).requestTimeoutSeconds());
  }

  @Test
  @DisplayName("save 不动已存在目录的权限")
  void saveLeavesAnExistingDirectoryAlone() throws Exception {
    // The parent is normally the workspace root; shared-posix deployments have other uids reading
    // agents/ and output/ through it, and narrowing it to 0700 is not undone by anything.
    // @TempDir arrives as 0700, where the narrowing is a no-op either way, so widen it first —
    // without this the assertion holds against the old code too and guards nothing.
    assumeTrue(
        dir.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "POSIX permissions are what this asserts");
    Set<PosixFilePermission> shared = PosixFilePermissions.fromString("rwxrwxr-x");
    Files.setPosixFilePermissions(dir, shared);

    McpConfigLoader loader = new McpConfigLoader(configFile());
    loader.save(List.of(new McpServerConfig("s", "stdio", "echo", Map.of(), "", Map.of(), 60)));

    assertEquals(shared, Files.getPosixFilePermissions(dir));
  }

  @Test
  @DisplayName("request_timeout 非整数或超出 1..3600 秒时 fail-loud")
  void requestTimeout_rejectsInvalidValues() throws Exception {
    for (String invalid : List.of("-1", "0", "3601", "99999999999", "1.5", "\"60\"")) {
      write(
          """
          servers:
            - name: bad-timeout
              transport: stdio
              command: echo
              request_timeout: %s
          """
              .formatted(invalid));

      IllegalArgumentException ex =
          assertThrows(
              IllegalArgumentException.class, () -> new McpConfigLoader(configFile()).loadRaw());

      assertTrue(ex.getMessage().contains("request_timeout"), ex::getMessage);
    }
  }
}
