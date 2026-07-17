package io.oryxos.provider;

import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
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
 *   <li><b>第一轮</b>（prompt 里还没有工具结果）：请求一次 {@code save_memory} 工具调用——这一步会真正写入 {@code
 *       MEMORY.md}，给全链路一个可观测的"文件写入"行为；
 *   <li><b>第二轮</b>（PromptBuilder 已把工具结果渲染成 {@code "tool: ..."} 行）：不再调工具，直接返回最终答复。
 * </ul>
 *
 * <p>判轮只看 prompt 文本里有没有出现工具结果行（{@link #TOOL_RESULT_MARK}）——无状态、确定性。只有"模型"是假的， ReActLoop /
 * ToolExecutor / Memory / Session / 审计全部走真实路径。
 */
public class MockChatModel implements ChatModel {

  /** PromptBuilder 把消息渲染成 "role: 内容" 行；用这两个行首判"最近一条消息"是工具结果还是用户消息。 */
  private static final String TOOL_LINE = "\ntool: ";

  private static final String USER_LINE = "\nuser: ";

  private static final String USER_MARK = "user: ";

  @Override
  public ChatResponse call(Prompt prompt) {
    String rendered = prompt.getContents();
    // 判轮看"最近一条消息"：工具结果行在用户行之后 → 工具已回填，第二轮收尾；否则最近是用户消息、第一轮触发 save_memory。
    // 不能只判"有没有 tool 行"——复用会话的历史里早有旧工具结果，会把每条新消息都误判成第二轮、永不再调工具。
    if (rendered.lastIndexOf(TOOL_LINE) > rendered.lastIndexOf(USER_LINE)) {
      return single(new AssistantMessage("好的，已经按你的要求记录并处理完成。"));
    }
    String fact = lastUserLine(rendered);
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

  /** 取渲染文本里最后一条 {@code "user: "} 行的内容，作为要记住的事实。 */
  private static String lastUserLine(String rendered) {
    int idx = rendered.lastIndexOf(USER_MARK);
    if (idx < 0) {
      return "（无用户消息）";
    }
    int start = idx + USER_MARK.length();
    int end = rendered.indexOf('\n', start);
    String line = end < 0 ? rendered.substring(start) : rendered.substring(start, end);
    return line.isBlank() ? "（空消息）" : line;
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
