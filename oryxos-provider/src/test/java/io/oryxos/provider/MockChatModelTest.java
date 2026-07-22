package io.oryxos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/** mock provider 的脚本行为回归：第一轮触发 save_memory、第二轮见到工具结果就收尾（31 节改结构化消息判轮）。 */
class MockChatModelTest {

  private final MockChatModel model = new MockChatModel();

  @Test
  @DisplayName("第一轮_触发save_memory工具调用且带用户内容")
  void firstTurn_emitsSaveMemoryToolCall() {
    // 最后一条是用户消息 → 第一轮
    ChatResponse resp = model.call(new Prompt(List.of(new UserMessage("记住：我在北京，怕冷"))));

    AssistantMessage out = resp.getResult().getOutput();
    assertTrue(out.hasToolCalls(), "第一轮应请求工具调用");
    AssistantMessage.ToolCall call = out.getToolCalls().get(0);
    assertEquals("save_memory", call.name());
    assertTrue(call.arguments().contains("北京"), "工具参数应带用户要记的内容");
  }

  @Test
  @DisplayName("第二轮_工具结果已回填则直接给最终答复")
  void secondTurn_returnsFinalTextWhenToolResultPresent() {
    // 最后一条是工具结果（ToolResponseMessage）→ 第二轮收尾
    ChatResponse resp =
        model.call(
            new Prompt(
                List.of(
                    new UserMessage("记住：我在北京"),
                    new ToolResponseMessage(
                        List.of(
                            new ToolResponseMessage.ToolResponse("id-1", "save_memory", "已记住"))))));

    AssistantMessage out = resp.getResult().getOutput();
    assertFalse(out.hasToolCalls(), "第二轮不应再调工具");
    assertFalse(out.getText().isBlank(), "第二轮应给出非空最终答复");
  }
}
