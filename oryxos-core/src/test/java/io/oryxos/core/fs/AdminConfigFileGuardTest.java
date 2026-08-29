package io.oryxos.core.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminConfigFileGuardTest {

  @TempDir Path temp;

  @Test
  @DisplayName("拒绝 channels.yaml / mcp_servers.yaml 直写（大小写不敏感）")
  void rejectsReservedConfigFiles() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/channels.yaml"));
    assertThrows(
        IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation("Channels.YAML"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/mcp_servers.yaml"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation("MCP_SERVERS.yaml"));
  }

  @Test
  @DisplayName("拒绝经保留文件名建子路径（防目录占位）")
  void rejectsAncestorPathViaReservedName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(".oryxos/channels.yaml/child.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation("mcp_servers.yaml/nested/x.yml"));
  }

  @Test
  @DisplayName("普通路径放行")
  void allowsOrdinaryPaths() {
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("agents/demo/notes.md"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("channels.yaml.bak"));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation((String) null));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation("  "));
    assertDoesNotThrow(() -> AdminConfigFileGuard.rejectMutation((Path) null));
  }

  @Test
  @DisplayName("软链叶子指向 channels.yaml 时拒绝")
  void rejectsSymlinkLeafToChannelsYaml() throws IOException {
    Path reserved = temp.resolve("channels.yaml");
    Files.writeString(reserved, "channels: []\n");
    Path alias = temp.resolve("alias.yaml");
    assumeCanSymlink(alias, reserved);

    assertThrows(IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation(alias));
    assertThrows(
        IllegalArgumentException.class,
        () -> AdminConfigFileGuard.rejectMutation(alias.toString()));
  }

  @Test
  @DisplayName("悬空软链目标词法为 mcp_servers.yaml 时也拒绝")
  void rejectsDanglingSymlinkNamedMcpServers() throws IOException {
    Path alias = temp.resolve("alias.yaml");
    assumeCanSymlink(alias, Path.of("mcp_servers.yaml"));

    assertThrows(IllegalArgumentException.class, () -> AdminConfigFileGuard.rejectMutation(alias));
  }

  private static void assumeCanSymlink(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (IOException | UnsupportedOperationException e) {
      assumeTrue(false, "当前环境无法创建软链: " + e.getMessage());
    }
  }
}
