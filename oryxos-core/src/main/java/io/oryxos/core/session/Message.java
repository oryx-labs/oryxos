package io.oryxos.core.session;

import java.util.List;

/**
 * 会话中的一条消息。role 三值：{@code user} / {@code assistant} / {@code tool}；toolName 仅 tool 角色非空。
 *
 * <p>为支持多步 ReAct（31 节修复）保留 OpenAI 工具调用配对：assistant 消息带它发起的 {@code toolCalls}（每个含模型分配的 id）， tool
 * 消息带它回应的 {@code toolCallId}。旧三参构造保留（user / 简单 assistant / 无 id 的 tool），旧会话 JSON 反序列化时新字段缺省为空。
 */
public record Message(
    String role, String content, String toolName, String toolCallId, List<ToolCall> toolCalls) {

  public static final String ROLE_USER = "user";
  public static final String ROLE_ASSISTANT = "assistant";
  public static final String ROLE_TOOL = "tool";

  public Message {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
  }

  public Message(String role, String content, String toolName) {
    this(role, content, toolName, null, List.of());
  }

  /** assistant 发起的一次工具调用（含模型分配的 id，回填结果时据此配对）。 */
  public record ToolCall(String id, String name, String argumentsJson) {}
}
