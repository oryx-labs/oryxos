package io.oryxos.core.skill;

import java.nio.file.Path;

/** Agent 本地 Skill 软连接解析出的有效绑定；正文不在该模型中，避免被误入常驻 prompt。 */
public record AgentSkillBinding(
    String agentName, String skillName, String description, Path linkPath, Path skillFile) {}
