package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.oryxos.core.profile.ProfileValidationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AgentNameTest {

  @TempDir Path agentsDir;

  @ParameterizedTest
  @ValueSource(strings = {"a", "Ops", "ops-agent", "ops_agent", "Agent_01-prod"})
  @DisplayName("仅接受现有 Agent 安全字符并保留原始展示名")
  void parse_acceptsSafeAsciiNames(String raw) {
    AgentName name = AgentName.parse(raw);

    assertEquals(raw, name.value());
    assertEquals(raw, name.toString());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", ".", "..", "../ops", "ops/name", "ops.name", "运维", "ops\nadmin"})
  @DisplayName("空值、路径字符、Unicode 与控制字符均拒绝")
  void parse_rejectsUnsafeNames(String raw) {
    assertThrows(IllegalArgumentException.class, () -> AgentName.parse(raw));
  }

  @Test
  @DisplayName("Agent 名最长 64 个 ASCII 字符")
  void parse_rejectsNamesLongerThanSixtyFourCharacters() {
    assertEquals(64, AgentName.parse("a".repeat(64)).value().length());
    assertThrows(IllegalArgumentException.class, () -> AgentName.parse("a".repeat(65)));
  }

  @Test
  @DisplayName("lock key 只对已校验 ASCII 名做 Locale 无关的小写化")
  void lockKey_isAsciiLowerCase() {
    assertEquals("ops_agent-01", AgentName.parse("Ops_AGENT-01").lockKey());
  }

  @Test
  @DisplayName("目录 basename 与 Profile.name 必须大小写也精确一致")
  void requireProfileName_requiresExactDirectoryMatch() {
    AgentName directoryName = AgentName.fromDirectory(agentsDir.resolve("Ops"));

    directoryName.requireProfileName("Ops");
    assertThrows(IllegalArgumentException.class, () -> directoryName.requireProfileName("ops"));
  }

  @Test
  @DisplayName("缺少 basename 或父目录的路径以领域异常拒绝")
  void directoryValidation_rejectsMissingPathComponentsWithoutNullPointer() {
    assertThrows(IllegalArgumentException.class, () -> AgentName.fromDirectory(null));
    Path filesystemRoot = agentsDir.toAbsolutePath().getRoot();
    assertThrows(IllegalArgumentException.class, () -> AgentName.fromDirectory(filesystemRoot));
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentName.parse("ops").requireFilesystemDirectoryName(filesystemRoot));
  }

  @Test
  @DisplayName("AgentLoader 拒绝目录 basename 与 frontmatter name 不一致")
  void deriveProfile_rejectsDirectoryProfileMismatch() throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve("Ops"));
    Files.writeString(
        dir.resolve("AGENT.md"),
        "---\nname: ops\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n正文");

    ProfileValidationException error =
        assertThrows(
            ProfileValidationException.class,
            () -> new AgentLoader(agentsDir, Set.of("deepseek")).deriveProfile(dir));

    assertEquals("Agent 目录名与 Profile.name 不一致: 目录=Ops, Profile=ops", error.getMessage());
  }

  @Test
  @DisplayName("AgentLoader 用统一解析器拒绝非法目录 basename")
  void deriveProfile_rejectsUnsafeDirectoryName() throws IOException {
    Path dir = Files.createDirectories(agentsDir.resolve("ops.team"));
    Files.writeString(
        dir.resolve("AGENT.md"),
        "---\nname: ops-team\nprovider:\n  name: deepseek\n  model: deepseek-chat\n---\n正文");

    ProfileValidationException error =
        assertThrows(
            ProfileValidationException.class,
            () -> new AgentLoader(agentsDir, Set.of("deepseek")).deriveProfile(dir));

    assertEquals("非法 Agent 目录名: ops.team", error.getMessage());
  }

  @Test
  @DisplayName("大小写不敏感文件系统上拒绝请求名与真实目录拼写不一致")
  void requireFilesystemDirectoryName_rejectsCaseAliasWhenSupported() throws IOException {
    Path actual = Files.createDirectory(agentsDir.resolve("Ops"));
    Path alias = agentsDir.resolve("ops");
    Assumptions.assumeTrue(Files.exists(alias) && Files.isSameFile(actual, alias));

    AgentName.parse("Ops").requireFilesystemDirectoryName(actual);
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentName.parse("ops").requireFilesystemDirectoryName(alias));
  }

  @Test
  @DisplayName("无关 dangling symlink 不得阻断正常 Agent 的目录身份校验")
  void requireFilesystemDirectoryName_ignoresUnstatableSibling() throws IOException {
    Path actual = Files.createDirectory(agentsDir.resolve("ops"));
    try {
      Files.createSymbolicLink(agentsDir.resolve("broken-link"), agentsDir.resolve("missing"));
    } catch (UnsupportedOperationException | IOException error) {
      Assumptions.abort("filesystem does not support creating a dangling symlink: " + error);
    }

    assertDoesNotThrow(() -> AgentName.parse("ops").requireFilesystemDirectoryName(actual));
  }
}
