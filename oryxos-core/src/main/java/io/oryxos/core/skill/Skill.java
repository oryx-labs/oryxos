package io.oryxos.core.skill;

/**
 * 一个全局可复用的 Skill（第 32 节）：一段用来约束 Agent 产出的指令/规范。
 *
 * <p>存储形态 {@code .oryxos/skills/<name>/SKILL.md}：frontmatter（name/description）+ 正文（约束指令）。 与"一个目录 =
 * 一个 Agent"的 {@code AGENT.md} 同构，但 Skill 不是 Agent、不进 {@code ProfileRegistry}：它是 Agent
 * 按名引用的**共享能力库**条目，由 {@code ContextLoader} 在组装 system prompt 时把正文注入（宪法 IV 修订：Skill 从"每-Agent
 * 子指令"升级为"全局能力库"）。
 */
public record Skill(String name, String description, String body) {

  public Skill {
    description = description == null ? "" : description;
    body = body == null ? "" : body;
  }
}
