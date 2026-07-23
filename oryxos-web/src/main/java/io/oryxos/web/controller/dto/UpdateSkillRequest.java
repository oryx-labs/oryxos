package io.oryxos.web.controller.dto;

/** PUT /skills/{name} 请求体：更新一个全局 Skill 的描述与正文（name 走路径，不可改）（第 32 节）。 */
public record UpdateSkillRequest(String description, String body) {}
