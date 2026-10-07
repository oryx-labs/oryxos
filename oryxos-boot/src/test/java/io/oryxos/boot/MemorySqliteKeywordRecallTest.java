package io.oryxos.boot;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.oryxos.cli.OryxOsRuntime;
import io.oryxos.core.agent.ToolExecutionContext;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.memory.MarkdownMemoryStore;
import io.oryxos.memory.MemoryServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 015 FR-002「关键词路跨后端档统一」：同一批归档条目、同一批关键词，sqlite 档（真库真 SQL）与 markdown 档必须给出同一组命中。
 *
 * <p>两档的判定规则只有一条：关键词与条目都按 {@link java.util.Locale#ROOT} 折叠，再做字面子串包含。sqlite 档若把这条规则交给 SQL—— {@code
 * LOWER(content) LIKE '%kw%'}——会同时丢掉两件事：SQLite 的 {@code lower()} 只折叠 ASCII（非 ASCII 大小写不成立）， 而
 * {@code LIKE} 把关键词里的 {@code %} / {@code _} 当通配符（字面量匹配不成立）。两者都只在 sqlite 档出现，正是 FR-002 要消掉的不一致。
 *
 * <p>走整机装配而不是接缝：真 {@code OryxOsRuntime} + {@code memory.backend=sqlite} + 临时 SQLite 库（Flyway 建表）+
 * 未配置向量化 （纯关键词路，即升级前的形态）。markdown 档由真 {@link MarkdownMemoryStore} 跑同一批数据作对照。每个用例用自己的 Agent 名， 记忆按
 * Agent 隔离，三个用例互不干扰。
 */
class MemorySqliteKeywordRecallTest {

  private static final List<String> ARCHIVAL =
      List.of(
          "发布事故复盘：CAFÉ 灰度回滚到上一版",
          "CPU 到 50% 时告警",
          "产能已经到 50 条上限",
          "连接串参数 a_b 用于测试环境",
          "标识符 axb 用于生产环境",
          "工单 OPS-4721 已升级到二线");

  private static Path root;
  private static Path markdownRoot;
  private static ConfigurableApplicationContext ctx;

  @BeforeAll
  static void boot() throws Exception {
    root = Files.createTempDirectory("oryxos-memory-keyword");
    markdownRoot = Files.createTempDirectory("oryxos-memory-keyword-md");
    ctx =
        new SpringApplicationBuilder(OryxOsRuntime.class)
            .run(
                "--oryxos.root=" + root,
                "--memory.backend=sqlite",
                "--oryxos.providers[0].name=mock",
                "--spring.datasource.url=jdbc:sqlite:" + root.resolve("oryxos.db"),
                "--spring.lifecycle.timeout-per-shutdown-phase=100ms",
                "--spring.main.web-application-type=none");
  }

  @AfterAll
  static void shutdown() {
    if (ctx != null) {
      ctx.close();
    }
  }

  @Test
  @DisplayName("非 ASCII 大小写：sqlite 档与 markdown 档同判（SQLite 的 lower() 只折 ASCII）")
  void nonAsciiCaseFoldsTheSameOnBothTiers() {
    assertTiersAgree(
        "mem-keyword-non-ascii",
        List.of(
            new Case("café", List.of(ARCHIVAL.get(0))),
            new Case("CAFÉ", List.of(ARCHIVAL.get(0)))));
  }

  @Test
  @DisplayName("关键词里的 % 与 _ 按字面匹配：sqlite 档与 markdown 档同判（LIKE 通配符不外泄）")
  void likeMetacharactersAreLiteralOnBothTiers() {
    assertTiersAgree(
        "mem-keyword-literals",
        List.of(
            // 50% 不得命中只含 50 的那条；a_b 不得命中 axb 那条
            new Case("50%", List.of(ARCHIVAL.get(1))),
            new Case("a_b", List.of(ARCHIVAL.get(3))),
            // 单个通配符本身也是字面量：% 只命中真写着 % 的那条，_ 只命中真写着 _ 的那条
            new Case("%", List.of(ARCHIVAL.get(1))),
            new Case("_", List.of(ARCHIVAL.get(3)))));
  }

  @Test
  @DisplayName("ASCII 大小写统一与无命中：两档同判（上面两条不得改坏原有那一半）")
  void asciiCaseAndMissingKeywordAreUnchanged() {
    assertTiersAgree(
        "mem-keyword-ascii",
        List.of(new Case("ops-4721", List.of(ARCHIVAL.get(5))), new Case("不存在的关键词", List.of())));
  }

  /** 同一批条目写入两档，逐个关键词断言两档命中都与期望一致（一次跑出全部不符项）。 */
  private static void assertTiersAgree(String agent, List<Case> cases) {
    MemoryServiceImpl sqlite = ctx.getBean(MemoryServiceImpl.class);
    MarkdownMemoryStore markdown = new MarkdownMemoryStore(markdownRoot);
    ToolExecutionContext.setAgentName(agent);
    try {
      for (String entry : ARCHIVAL) {
        sqlite.remember(entry, MemoryScope.ARCHIVAL);
        markdown.append(entry, MemoryScope.ARCHIVAL);
      }
      List<Executable> checks = new ArrayList<>();
      for (Case c : cases) {
        checks.add(
            () ->
                assertEquals(
                    c.expected(),
                    hits(sqlite.recall(c.keyword())),
                    "sqlite 档 keyword=\"" + c.keyword() + "\""));
        checks.add(
            () ->
                assertEquals(
                    c.expected(),
                    hits(markdown.recallByKeyword(c.keyword())),
                    "markdown 档 keyword=\"" + c.keyword() + "\""));
      }
      assertAll(checks);
    } finally {
      ToolExecutionContext.clear();
    }
  }

  /** 返回的行折回条目原文（markdown 档行首带时间戳，按包含判定），保持写入序。 */
  private static List<String> hits(List<String> lines) {
    return ARCHIVAL.stream()
        .filter(entry -> lines.stream().anyMatch(line -> line.contains(entry)))
        .toList();
  }

  private record Case(String keyword, List<String> expected) {}
}
