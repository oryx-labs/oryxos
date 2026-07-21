package io.oryxos.core.provider;

import io.oryxos.core.OryxTool;
import io.oryxos.core.session.Message;
import java.util.List;

/**
 * 一次模型调用的输入：系统提示 + **结构化对话消息**（保留 user/assistant(tool_calls)/tool(tool_call_id) 配对）+ 可用工具。
 *
 * <p>31 节修复：历史不再拍平成一段文本——多步 ReAct 里模型必须能从结构化消息看出"某工具已调过、结果是 X"，否则会反复重调同一个工具。 systemPrompt
 * 承载不随对话变的部分（ContextLoader/记忆/日期），messages 承载对话本身。
 */
public record ProviderRequest(
    String systemPrompt, List<Message> messages, List<OryxTool> availableTools) {

  public ProviderRequest {
    messages = messages == null ? List.of() : List.copyOf(messages);
    availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
  }

  /** 单轮：content 作为一条 user 消息，无系统提示、无工具。 */
  public static ProviderRequest of(String content) {
    return new ProviderRequest(
        null, List.of(new Message(Message.ROLE_USER, content, null)), List.of());
  }

  /** 兼容旧两参用法（content + tools）：content 作为单条 user 消息。 */
  public ProviderRequest(String content, List<OryxTool> availableTools) {
    this(null, List.of(new Message(Message.ROLE_USER, content, null)), availableTools);
  }
}
