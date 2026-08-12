package io.oryxos.core.skill;

/**
 * 一个全局可复用的 Skill（第 32 节）：一段用来约束 Agent 产出的指令/规范。
 *
 * <p>存储形态 {@code .oryxos/skills/<name>/SKILL.md}：frontmatter（name/description）+ 正文（约束指令）。 与"一个目录 =
 * 一个 Agent"的 {@code AGENT.md} 同构，但 Skill 不是 Agent、不进 {@code ProfileRegistry}：它是 Agent
 * 通过本地软连接绑定的共享能力条目。{@code ContextLoader} 只披露元数据与 Agent 本地入口，正文由 {@code read_file} 按需读取。
 */
public record Skill(String name, String description, String body) {

  public Skill {
    description = description == null ? "" : description;
    body = body == null ? "" : body;
  }
}
