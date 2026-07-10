package io.oryxos.core.session;

/** 会话中的一条消息。role 三值：{@code user} / {@code assistant} / {@code tool}； toolName 仅 tool 角色非空。 */
public record Message(String role, String content, String toolName) {

  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";
}
