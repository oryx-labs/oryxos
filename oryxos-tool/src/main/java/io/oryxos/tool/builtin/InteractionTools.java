package io.oryxos.tool.builtin;

import io.oryxos.tool.interaction.UserInteraction;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 内置交互工具：ask_user——Agent 在处理中途向人提问 / 请求确认（human-in-the-loop）。
 *
 * <p>危险操作前找人拍板的落点：具体怎么问由 {@link UserInteraction} 决定（CLI 读终端、 无人值守渠道直接报不支持），本工具只负责把问题递过去、把回答带回来。
 */
public class InteractionTools {

  private final UserInteraction interaction;

  public InteractionTools(UserInteraction interaction) {
    this.interaction = interaction;
  }

  @Tool(name = "ask_user", description = "向当前用户提出一个问题并等待其回答（用于澄清需求或确认危险操作）")
  public String askUser(@ToolParam(description = "要问用户的问题") String question) {
    return interaction.ask(question);
  }
}
