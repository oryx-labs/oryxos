package io.oryxos.tool.sandbox;

/** 一次待校验的涉外动作：类型 + 目标（路径 / 完整命令 / URL）。 */
public record SandboxAction(ActionType type, String target) {}
