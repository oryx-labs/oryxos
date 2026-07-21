package io.oryxos.core.session;

import io.oryxos.core.ToolResult;
import io.oryxos.core.provider.ProviderResponse;
import io.oryxos.core.provider.ToolCallRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话的全部状态：会话标识、所属 Agent、按序累积的消息。
 *
 * <p>前向最小（17 节）：只做循环的输入与累积容器；sessionId 在此只透传——拼接规则归 18 节 SessionManager（H4④），持久化实体与 Repository 同归
 * 18 节。
 *
 * <p>顺序不变量（FR-003）：消息严格按发生序追加，事后可完整回放。
 */
public class Session {

  private final String sessionId;
  private final String profileName;
  private final List<Message> messages = new ArrayList<>();

  public Session(String sessionId, String profileName) {
    this.sessionId = sessionId;
    this.profileName = profileName;
  }

  /** 恢复构造器（18 节）：从持久化历史还原会话——17 节 research D2 预告的改造点。 */
  public Session(String sessionId, String profileName, List<Message> restored) {
    this(sessionId, profileName);
    if (restored != null) {
      this.messages.addAll(restored);
    }
  }

  public String sessionId() {
    return sessionId;
  }

  public String profileName() {
    return profileName;
  }

  /** 对外只读视图；累积只能走 append 三兄弟。 */
  public List<Message> messages() {
    return List.copyOf(messages);
  }

  public void appendUser(String content) {
    messages.add(new Message(Message.ROLE_USER, content, null));
  }

  /** 累积模型响应；text 为 null 时按空串。带上模型这一轮发起的 tool_calls（含 id），下一轮回填结果时据此配对。 */
  public void appendAssistant(ProviderResponse response) {
    String text = response.text() == null ? "" : response.text();
    List<Message.ToolCall> calls =
        response.toolCalls().stream()
            .map(c -> new Message.ToolCall(c.id(), c.name(), c.argumentsJson()))
            .toList();
    messages.add(new Message(Message.ROLE_ASSISTANT, text, null, null, calls));
  }

  /** 累积工具结果：成功存 content、失败存错误描述——成败都进历史；带上 tool_call_id 与发起的 assistant tool_call 配对。 */
  public void appendToolResult(ToolCallRequest call, ToolResult result) {
    String content = result.success() ? result.content() : "工具执行失败: " + result.errorMessage();
    messages.add(
        new Message(
            Message.ROLE_TOOL, content == null ? "" : content, call.name(), call.id(), List.of()));
  }
}
