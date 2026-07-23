package io.oryxos.core.agent;

import io.oryxos.core.context.MarkdownFrontmatter;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * 把一份 {@code AGENT.md} 文本拆成 frontmatter（YAML 配置）与正文（任务指令）。
 *
 * <p>一个 Agent 目录 = 一个 Agent（第 29 节）：frontmatter 由 {@code AgentLoader} 派生成 Profile， 正文由 {@code
 * ContextLoader} 注入 system prompt——两者共用本拆分器。 形态：文件以一行 {@code ---} 开头、到下一行 {@code ---} 之间为
 * frontmatter，其后为正文； 无 frontmatter 围栏时，整篇当正文、frontmatter 为空。
 */
public final class AgentMarkdown {

  private AgentMarkdown() {}

  /** 拆分结果：frontmatter 不可变、缺省为空 Map；body 为去掉围栏后的正文。 */
  public record Parsed(Map<String, Object> frontmatter, String body) {
    public Parsed {
      frontmatter = frontmatter == null ? Map.of() : Map.copyOf(frontmatter);
      body = body == null ? "" : body;
    }
  }

  public static Parsed split(String content) {
    MarkdownFrontmatter.Split split = MarkdownFrontmatter.split(content);
    if (!split.hasFrontmatter()) {
      return new Parsed(Map.of(), split.body());
    }
    return new Parsed(parseYaml(split.yaml()), split.body());
  }

  private static Map<String, Object> parseYaml(String text) {
    if (text.isBlank()) {
      return Map.of();
    }
    Object loaded = new Yaml().load(text);
    if (loaded instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return typed;
    }
    return Map.of();
  }
}
