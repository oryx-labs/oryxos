package io.oryxos.provider;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 独立的 mock provider——挂在显式映射表的 {@code "mock"} 名下（宪法 III），不连任何真实模型、不需要 key/网络。
 *
 * <p>按脚本驱动一次确定性的 ReAct，供无 key 的全链路自测：
 *
 * <ul>
 *   <li><b>第一轮</b>（最后一条消息还是用户消息）：请求一次 {@code save_memory} 工具调用——这一步会真正写入 {@code
 *       MEMORY.md}，给全链路一个可观测的"文件写入"行为；
 *   <li><b>第二轮</b>（最后一条消息已是工具结果 {@link ToolResponseMessage}）：不再调工具，直接返回最终答复。
 * </ul>
 *
 * <p>判轮看"最后一条结构化消息"的类型（31 节改结构化消息透传后）——无状态、确定性。只有"模型"是假的， ReActLoop / ToolExecutor / Memory /
 * Session / 审计全部走真实路径。
 */
public class MockChatModel implements ChatModel {

  @Override
  public ChatResponse call(Prompt prompt) {
    List<Message> messages = prompt.getInstructions();
    Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
    // 最后一条是工具结果 → 工具已回填，第二轮收尾；否则最近是用户消息、第一轮触发 save_memory。
    if (last instanceof ToolResponseMessage) {
      return single(new AssistantMessage("好的，已经按你的要求记录并处理完成。"));
    }
    String fact = lastUserText(messages);
    AssistantMessage.ToolCall toolCall =
        new AssistantMessage.ToolCall(
            "mock-call-1",
            "function",
            "save_memory",
            "{\"content\":" + jsonString(fact) + ",\"scope\":\"archival\"}");
    return single(new AssistantMessage("", Map.of(), List.of(toolCall)));
  }

  private static ChatResponse single(AssistantMessage message) {
    return new ChatResponse(List.of(new Generation(message)));
  }

  /** 取最后一条用户消息的文本，作为要记住的事实。 */
  private static String lastUserText(List<Message> messages) {
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof UserMessage) {
        String text = ((UserMessage) messages.get(i)).getText();
        return text == null || text.isBlank() ? "（空消息）" : text;
      }
    }
    return "（无用户消息）";
  }

  private static String jsonString(String value) {
    String escaped =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    return "\"" + escaped + "\"";
  }
}
