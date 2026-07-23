package io.oryxos.web.controller.dto;

/** POST /skills 请求体：新建一个全局 Skill（name + 描述 + 正文=约束指令）（第 32 节）。 */
public record CreateSkillRequest(String name, String description, String body) {}
