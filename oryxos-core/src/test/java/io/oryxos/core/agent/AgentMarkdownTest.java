package io.oryxos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AGENT.md 拆分器：frontmatter/正文分离。 */
class AgentMarkdownTest {

  @Test
  @DisplayName("拆出 frontmatter 与正文")
  void split_separatesFrontmatterAndBody() {
    String content = "---\nname: ops\nprovider: deepseek\n---\n你是运维助手。\n第一步做 X。";
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(content);
    assertEquals("ops", parsed.frontmatter().get("name"));
    assertEquals("deepseek", parsed.frontmatter().get("provider"));
    assertTrue(parsed.body().startsWith("你是运维助手。"), "正文从围栏之后开始");
    assertEquals(-1, parsed.body().indexOf("name: ops"), "frontmatter 不落进正文");
  }

  @Test
  @DisplayName("无 frontmatter 围栏时整篇当正文")
  void split_noFrontmatter_wholeIsBody() {
    AgentMarkdown.Parsed parsed = AgentMarkdown.split("就是一段纯正文，没有围栏。");
    assertTrue(parsed.frontmatter().isEmpty(), "frontmatter 为空");
    assertEquals("就是一段纯正文，没有围栏。", parsed.body());
  }

  @Test
  @DisplayName("空 frontmatter 或空内容不炸")
  void split_emptyInputs_areSafe() {
    assertTrue(AgentMarkdown.split("").frontmatter().isEmpty());
    assertEquals("", AgentMarkdown.split("").body());
    AgentMarkdown.Parsed emptyFm = AgentMarkdown.split("---\n---\n只有正文");
    assertTrue(emptyFm.frontmatter().isEmpty());
    assertEquals("只有正文", emptyFm.body());
  }

  @Test
  @DisplayName("正文里含 --- 不误判为围栏闭合位置之后")
  void split_bodyContainingDashes_keptInBody() {
    String content = "---\nname: ops\n---\n正文第一行\n---\n正文里的分隔线";
    AgentMarkdown.Parsed parsed = AgentMarkdown.split(content);
    assertEquals("ops", parsed.frontmatter().get("name"));
    assertTrue(parsed.body().contains("正文里的分隔线"), "首个闭合围栏之后的 --- 属于正文");
  }
}
