package io.oryxos.core.agent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

  private static final String FENCE = "---";
  private static final int QUOTED_KEY_MIN_LENGTH = 2;
  private static final char SINGLE_QUOTE = '\'';
  private static final char DOUBLE_QUOTE = '"';

  private AgentMarkdown() {}

  /** 拆分结果：frontmatter 不可变、缺省为空 Map；body 为去掉围栏后的正文。 */
  public record Parsed(Map<String, Object> frontmatter, String body) {
    public Parsed {
      frontmatter = frontmatter == null ? Map.of() : Map.copyOf(frontmatter);
      body = body == null ? "" : body;
    }
  }

  public static Parsed split(String content) {
    if (content == null || content.isEmpty()) {
      return new Parsed(Map.of(), "");
    }
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    if (lines.length == 0 || !FENCE.equals(lines[0].strip())) {
      return new Parsed(Map.of(), content.strip());
    }
    int close = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.equals(lines[i].strip())) {
        close = i;
        break;
      }
    }
    if (close < 0) {
      // 只有开头围栏、没有闭合——按无 frontmatter 处理（不猜测半截 YAML）
      return new Parsed(Map.of(), content.strip());
    }
    String frontmatterText = String.join("\n", Arrays.copyOfRange(lines, 1, close));
    String body = String.join("\n", Arrays.copyOfRange(lines, close + 1, lines.length)).strip();
    return new Parsed(parseYaml(frontmatterText), body);
  }

  /** Returns the legacy top-level skills list, rejecting non-list or non-string values. */
  public static List<String> legacySkills(String content) {
    Object value = split(content).frontmatter().get("skills");
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("旧版顶层 skills 必须是字符串列表");
    }
    List<String> names = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String name) || name.isBlank()) {
        throw new IllegalArgumentException("旧版顶层 skills 必须是非空字符串列表");
      }
      names.add(name);
    }
    return List.copyOf(names);
  }

  /** Removes only the top-level skills YAML block while preserving all other lines and endings. */
  public static String removeLegacySkills(String content) {
    if (content == null || content.isEmpty()) {
      return content;
    }
    String newline = content.contains("\r\n") ? "\r\n" : "\n";
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    if (lines.length == 0 || !FENCE.equals(lines[0].strip())) {
      return content;
    }
    int close = -1;
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.equals(lines[i].strip())) {
        close = i;
        break;
      }
    }
    if (close < 0) {
      return content;
    }
    List<String> kept = new ArrayList<>();
    kept.add(lines[0]);
    boolean removed = false;
    for (int i = 1; i < close; i++) {
      String line = lines[i];
      if (isTopLevelKey(line, "skills")) {
        removed = true;
        while (i + 1 < close && isLegacyValueLine(lines[i + 1])) {
          i++;
        }
        continue;
      }
      kept.add(line);
    }
    if (!removed) {
      return content;
    }
    for (int i = close; i < lines.length; i++) {
      kept.add(lines[i]);
    }
    return String.join(newline, kept);
  }

  public static boolean hasLegacySkills(String content) {
    return split(content).frontmatter().containsKey("skills");
  }

  /** Replaces all matching top-level scalars, or inserts one when absent. */
  public static String replaceTopLevelScalar(String content, String key, String value) {
    if (content == null || content.isEmpty()) {
      throw new IllegalArgumentException("Markdown 内容为空");
    }
    String newline = content.contains("\r\n") ? "\r\n" : "\n";
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    if (lines.length == 0 || !FENCE.equals(lines[0].strip())) {
      throw new IllegalArgumentException("Markdown 缺少 frontmatter");
    }
    int close = -1;
    boolean replaced = false;
    for (int i = 1; i < lines.length; i++) {
      if (FENCE.equals(lines[i].strip())) {
        close = i;
        break;
      }
      if (isTopLevelKey(lines[i], key)) {
        lines[i] = key + ": " + value;
        replaced = true;
      }
    }
    if (close < 0) {
      throw new IllegalArgumentException("Markdown frontmatter 未闭合");
    }
    if (replaced) {
      return String.join(newline, lines);
    }
    List<String> inserted = new ArrayList<>(Arrays.asList(lines));
    inserted.add(1, key + ": " + value);
    return String.join(newline, inserted);
  }

  private static boolean isTopLevelKey(String line, String key) {
    if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
      return false;
    }
    int colon = line.indexOf(':');
    if (colon < 0) {
      return false;
    }
    String candidate = line.substring(0, colon).strip();
    if (candidate.length() >= QUOTED_KEY_MIN_LENGTH) {
      char first = candidate.charAt(0);
      char last = candidate.charAt(candidate.length() - 1);
      if (hasMatchingQuotes(first, last)) {
        candidate = candidate.substring(1, candidate.length() - 1);
      }
    }
    return key.equals(candidate);
  }

  private static boolean hasMatchingQuotes(char first, char last) {
    if (first == SINGLE_QUOTE) {
      return last == SINGLE_QUOTE;
    }
    if (first == DOUBLE_QUOTE) {
      return last == DOUBLE_QUOTE;
    }
    return false;
  }

  private static boolean isIndented(String line) {
    return !line.isEmpty() && Character.isWhitespace(line.charAt(0));
  }

  private static boolean isLegacyValueLine(String line) {
    return isIndented(line)
        || (!line.isEmpty()
            && line.charAt(0) == '-'
            && (line.length() == 1 || Character.isWhitespace(line.charAt(1))));
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
