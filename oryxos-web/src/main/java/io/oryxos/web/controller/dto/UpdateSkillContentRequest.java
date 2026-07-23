package io.oryxos.web.controller.dto;

/** Replaces the public Skill's SKILL.md after server-side validation. */
public record UpdateSkillContentRequest(String content) {}
