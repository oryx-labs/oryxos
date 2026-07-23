package io.oryxos.web.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 32 节验收：从 GitHub 目录 URL 递归拉整个文件夹，不是抓网页正文。 */
class GithubFolderFetcherTest {

  @Test
  @DisplayName("parseTreeUrl：解析 owner/repo/branch/path，末段作为 fallbackName")
  void parseTreeUrl_parsesAllParts() {
    GithubFolderFetcher.Target target =
        GithubFolderFetcher.parseTreeUrl(
            "https://github.com/obra/superpowers/tree/main/skills/brainstorming");

    assertEquals("obra", target.owner());
    assertEquals("superpowers", target.repo());
    assertEquals("main", target.branch());
    assertEquals("skills/brainstorming", target.path());
    assertEquals("brainstorming", target.fallbackName());
  }

  @Test
  @DisplayName("parseTreeUrl：非 GitHub 目录 URL 一律拒绝（不是 tree/ 形式、不是 github.com）")
  void parseTreeUrl_rejectsNonGithubOrNonTreeUrls() {
    assertThrows(
        IllegalArgumentException.class,
        () -> GithubFolderFetcher.parseTreeUrl("https://example.com/a/b/tree/main/c"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            GithubFolderFetcher.parseTreeUrl(
                "https://raw.githubusercontent.com/obra/superpowers/main/skills/brainstorming/SKILL.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> GithubFolderFetcher.parseTreeUrl("https://github.com/obra/superpowers"));
  }

  @Test
  @DisplayName("fetchFolder：递归子目录，按相对路径落到 Map，不止一份 SKILL.md")
  void fetchFolder_recursesAndCollectsAllFiles() {
    Map<String, String> responses = new HashMap<>();
    responses.put(
        "https://api.github.com/repos/obra/superpowers/contents/skills/brainstorming?ref=main",
        """
        [
          {"type":"file","path":"skills/brainstorming/SKILL.md",
           "download_url":"https://raw.example.com/SKILL.md"},
          {"type":"dir","path":"skills/brainstorming/scripts"}
        ]
        """);
    responses.put(
        "https://api.github.com/repos/obra/superpowers/contents/skills/brainstorming/scripts?ref=main",
        """
        [
          {"type":"file","path":"skills/brainstorming/scripts/run.py",
           "download_url":"https://raw.example.com/run.py"}
        ]
        """);
    responses.put("https://raw.example.com/SKILL.md", "---\nname: brainstorming\n---\n\n正文");
    responses.put("https://raw.example.com/run.py", "print('hi')");

    GithubFolderFetcher fetcher = new GithubFolderFetcher(uri -> responses.get(uri.toString()));
    GithubFolderFetcher.Target target =
        GithubFolderFetcher.parseTreeUrl(
            "https://github.com/obra/superpowers/tree/main/skills/brainstorming");

    Map<String, String> files = fetcher.fetchFolder(target);

    assertEquals(2, files.size());
    assertTrue(files.get("SKILL.md").contains("name: brainstorming"));
    assertEquals("print('hi')", files.get("scripts/run.py"));
  }

  @Test
  @DisplayName("fetchFolder：目录不存在/为空时拒绝")
  void fetchFolder_emptyDirectory_rejected() {
    GithubFolderFetcher fetcher = new GithubFolderFetcher(uri -> "[]");
    GithubFolderFetcher.Target target =
        GithubFolderFetcher.parseTreeUrl("https://github.com/a/b/tree/main/empty");

    assertThrows(IllegalArgumentException.class, () -> fetcher.fetchFolder(target));
  }

  @Test
  @DisplayName("fetchFolder：非数组响应（GitHub 对单文件路径返回对象）视为非法目录")
  void fetchFolder_nonArrayResponse_rejected() {
    GithubFolderFetcher fetcher =
        new GithubFolderFetcher(uri -> "{\"type\":\"file\",\"path\":\"x\"}");
    GithubFolderFetcher.Target target =
        GithubFolderFetcher.parseTreeUrl("https://github.com/a/b/tree/main/x");

    assertThrows(IllegalArgumentException.class, () -> fetcher.fetchFolder(target));
  }

  @Test
  @DisplayName("空 URL 直接拒绝")
  void parseTreeUrl_nullUrl_rejected() {
    assertThrows(IllegalArgumentException.class, () -> GithubFolderFetcher.parseTreeUrl(null));
  }
}
